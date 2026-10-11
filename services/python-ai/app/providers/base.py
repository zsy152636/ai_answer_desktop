"""Provider 抽象层。

统一职责：
- 声明 chat / complete / health_probe 能力与 endpoint 语义。
- 处理 HTTP 调用：url_guard 校验 → 逐跳重定向校验 → 响应体上限 → 故障分类。
- 密钥只随请求头传递，绝不写入日志或错误消息。
"""

from __future__ import annotations

import json
import logging
from abc import ABC, abstractmethod
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any, Final

import httpx

from ..errors import (
    AiError,
    ErrorCategory,
    ErrorDetail,
    classify_http_status,
    safe_exception_text,
)
from ..schemas import LlmCallProfile
from ..security.redaction import redact
from ..security.url_guard import (
    IpResolver,
    UrlVerdict,
    guarded_request,
    validate_url,
)
from ..utils import elapsed_ms, monotonic_ms
logger = logging.getLogger("python_ai.providers")

MAX_RESPONSE_BYTES: Final[int] = 1024 * 1024  # 1 MiB（契约 §6）
ERROR_BODY_SNIPPET_CHARS: Final[int] = 400
MIN_THINKING_MODEL_MAX_TOKENS: Final[int] = 1024

HttpClientFactory = Callable[[LlmCallProfile], httpx.AsyncClient]


@dataclass(slots=True)
class ChatMessage:
    role: str
    content: str

    def to_dict(self) -> dict[str, str]:
        return {"role": self.role, "content": self.content}


@dataclass(slots=True)
class ChatResult:
    """一次对话补全的结果。"""

    content: str
    model: str
    usage: dict[str, Any] = field(default_factory=dict)
    latency_ms: int = 0
    http_status: int = 200
    reasoning_only: bool = False


@dataclass(slots=True)
class EndpointProbe:
    """端点能力探测结果。"""

    reachable: bool
    models: list[str] = field(default_factory=list)
    chat_completions: bool = False
    models_endpoint: bool = False
    http_status: int | None = None
    category: ErrorCategory = ErrorCategory.UNAVAILABLE
    message: str = ""


@dataclass(slots=True)
class FetchOutcome:
    """一次 HTTP 调用的原始结果（不因供应商 HTTP 错误码抛异常）。

    `_request` 走的是「业务调用」语义（供应商错误直接抛分类异常）；
    test-connection / probe 需要拿到供应商的原始状态码，因此单独保留本结构。
    """

    status: int | None
    payload: Any = None
    latency_ms: int = 0
    category: ErrorCategory = ErrorCategory.OK
    message: str = ""

    @property
    def ok(self) -> bool:
        return self.category is ErrorCategory.OK and self.status is not None and 200 <= self.status < 300


def default_client_factory(profile: LlmCallProfile) -> httpx.AsyncClient:
    """默认客户端：不跟随重定向、超时取 profile、禁用环境代理避免绕过校验。"""
    return httpx.AsyncClient(
        timeout=profile.httpx_timeout,
        follow_redirects=False,
        trust_env=False,
        headers={
            "accept": "application/json",
            "user-agent": "ai-answer-desktop/python-ai",
        },
    )


class LlmProvider(ABC):
    """LLM 供应商适配器抽象。"""

    name: str = "unknown"

    def __init__(
        self,
        profile: LlmCallProfile,
        *,
        client_factory: HttpClientFactory | None = None,
        resolver: IpResolver | None = None,
    ) -> None:
        self.profile = profile
        self._client_factory = client_factory or default_client_factory
        self._resolver = resolver

    # ---------------------------------------------------------------- endpoint 约定
    @abstractmethod
    def chat_path(self) -> str:
        """对话补全相对路径。"""

    @abstractmethod
    def probe_path(self) -> str:
        """轻量探测相对路径（test-connection 用最小请求）。"""

    @abstractmethod
    def parse_models(self, payload: Any) -> list[str]:
        """从探测响应里提取模型名列表。"""

    # ---------------------------------------------------------------- URL 与请求头
    def build_url(self, path: str) -> str:
        base = self.profile.base_url.rstrip("/")
        tail = path if path.startswith("/") else f"/{path}"
        return f"{base}{tail}"

    def validate_base_url(self) -> UrlVerdict:
        """调用前校验 baseUrl（含 DNS 解析与私网判定）。"""
        return validate_url(
            self.profile.base_url,
            allow_local_network=self.profile.allow_local_network,
            resolver=self._resolver,
        )

    def auth_headers(self) -> dict[str, str]:
        """鉴权头。绝不把密钥写进日志；Ollama 本地不带密钥也能调用。"""
        if self.profile.api_key:
            return {"authorization": f"Bearer {self.profile.api_key}"}
        return {}

    # ---------------------------------------------------------------- 统一 HTTP 调用
    async def _request(
        self,
        method: str,
        url: str,
        *,
        json_body: dict[str, Any] | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> tuple[int, Any, dict[str, str], int]:
        """发送请求并解析 JSON；返回 (status, payload, headers, latency_ms)。

        - 响应体超过 1 MiB 直接拒绝（分类 protocol）。
        - 网络异常按 dns/tls/timeout/unavailable 分类。
        """
        owns_client = client is None
        active_client = client or self._client_factory(self.profile)
        headers = self.auth_headers()
        started = monotonic_ms()
        try:
            response = await guarded_request(
                active_client,
                method,
                url,
                allow_local_network=self.profile.allow_local_network,
                resolver=self._resolver,
                headers=headers,
                json=json_body,
            )
            body = await self._read_limited(response)
        except AiError:
            raise
        except httpx.TimeoutException as exc:
            raise AiError.from_category(
                ErrorCategory.TIMEOUT,
                message="连接模型服务超时",
                http_status=None,
                cause=exc,
            ) from exc
        except httpx.TooManyRedirects as exc:  # pragma: no cover - 已手动处理
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="重定向次数过多", cause=exc
            ) from exc
        except httpx.ConnectError as exc:
            raise self._classify_connect_error(exc) from exc
        except httpx.TransportError as exc:
            raise AiError.from_category(
                ErrorCategory.UNAVAILABLE,
                message="与模型服务的连接中断",
                cause=exc,
            ) from exc
        finally:
            if owns_client:
                await active_client.aclose()

        latency_ms = elapsed_ms(started)
        logger.debug(
            "provider=%s method=%s status=%s latencyMs=%s",
            self.name,
            method,
            response.status_code,
            latency_ms,
        )
        snippet = body[:ERROR_BODY_SNIPPET_CHARS]
        if response.status_code >= 400:
            category = classify_http_status(response.status_code, snippet)
            # http_status 保持契约映射（如 auth→502），供应商状态码只作为字段级明细
            raise AiError.from_category(
                category,
                message=f"模型服务返回 HTTP {response.status_code}（{category.value}）",
                details=[
                    ErrorDetail(
                        field="providerStatus", message=str(response.status_code)
                    ),
                    *_http_error_details(snippet),
                ],
            )
        payload = _parse_json_body(body)
        return response.status_code, payload, dict(response.headers), latency_ms

    async def fetch_raw(
        self,
        method: str,
        url: str,
        *,
        client: httpx.AsyncClient | None = None,
    ) -> FetchOutcome:
        """连接测试/探测专用：供应商 HTTP 错误码不抛异常，返回原始状态。

        安全策略拒绝（blocked）仍然抛出：那是本服务的本地判定，不属于
        “供应商返回了什么”，不能伪装成探测结果。
        """
        started = monotonic_ms()
        try:
            status, payload, _headers, _latency = await self._request(
                method, url, client=client
            )
        except AiError as exc:
            if exc.category is ErrorCategory.BLOCKED:
                raise
            provider_status = _provider_status_of(exc)
            return FetchOutcome(
                status=provider_status,
                latency_ms=elapsed_ms(started),
                category=exc.category,
                message=exc.message,
            )
        return FetchOutcome(
            status=status,
            payload=payload,
            latency_ms=elapsed_ms(started),
            category=classify_http_status(status),
            message="",
        )

    @staticmethod
    async def _read_limited(response: httpx.Response) -> str:
        """带响应体上限的读取（默认 1 MiB），超限归为 protocol。"""
        chunks: list[bytes] = []
        total = 0
        async for chunk in response.aiter_bytes():
            total += len(chunk)
            if total > MAX_RESPONSE_BYTES:
                raise AiError.from_category(
                    ErrorCategory.PROTOCOL, message="模型服务响应体超过 1 MiB 上限"
                )
            chunks.append(chunk)
        raw = b"".join(chunks)
        return raw.decode("utf-8", errors="replace")

    def _classify_connect_error(self, exc: httpx.ConnectError) -> AiError:
        """连接阶段异常细分：证书/代理/解析/网络不可达。"""
        text = safe_exception_text(exc).lower()
        if "certificate" in text or "ssl" in text or "tls" in text:
            return AiError.from_category(
                ErrorCategory.TLS, message="TLS 握手失败或证书不可信", cause=exc
            )
        if "getaddrinfo" in text or "name or service not known" in text or "nodename" in text:
            return AiError.from_category(
                ErrorCategory.DNS, message="无法解析模型服务域名", cause=exc
            )
        return AiError.from_category(
            ErrorCategory.DNS,
            message="无法连接到模型服务（域名解析或端口不可达）",
            cause=exc,
        )

    # ---------------------------------------------------------------- 能力接口
    @abstractmethod
    async def chat(
        self,
        messages: Sequence[ChatMessage],
        *,
        client: httpx.AsyncClient | None = None,
        max_tokens: int | None = None,
        json_mode: bool = False,
    ) -> ChatResult:
        """对话补全。"""

    async def complete(
        self,
        prompt: str,
        *,
        system: str | None = None,
        client: httpx.AsyncClient | None = None,
        max_tokens: int | None = None,
        json_mode: bool = False,
    ) -> ChatResult:
        """单轮补全（chat 的便捷封装，避免各 provider 重复实现）。"""
        messages: list[ChatMessage] = []
        if system:
            messages.append(ChatMessage("system", system))
        messages.append(ChatMessage("user", prompt))
        return await self.chat(
            messages, client=client, max_tokens=max_tokens, json_mode=json_mode
        )

    @abstractmethod
    async def health_probe(
        self, *, client: httpx.AsyncClient | None = None
    ) -> EndpointProbe:
        """最小化探测（不发送对话请求，不因 401 判定不可达）。"""

    # ---------------------------------------------------------------- 结果解析工具
    def extract_content(self, message: dict[str, Any]) -> tuple[str, bool]:
        """提取 content；思考型模型 content 为空时回退 reasoning_content。

        两者都为空 → protocol 分类（契约要求的错误归类）。
        """
        content = message.get("content")
        text = content.strip() if isinstance(content, str) else ""
        if text:
            return text, False
        reasoning = message.get("reasoning_content") or message.get("reasoning")
        reasoning_text = reasoning.strip() if isinstance(reasoning, str) else ""
        if reasoning_text:
            logger.info("provider=%s content 为空，回退 reasoning_content", self.name)
            return reasoning_text, True
        raise AiError.from_category(
            ErrorCategory.PROTOCOL, message="模型服务返回了空内容（content 与 reasoning 均为空）"
        )


def _parse_json_body(body: str) -> Any:
    """解析响应体为 JSON；失败按 protocol 分类（不泄漏原始正文）。"""
    if not body.strip():
        return None
    try:
        return json.loads(body)
    except json.JSONDecodeError as exc:
        raise AiError.from_category(
            ErrorCategory.PROTOCOL,
            message="模型服务返回的不是合法 JSON（协议不兼容）",
            details=[{"field": "body", "message": "invalid json"}],
            cause=exc,
        ) from exc


def _http_error_details(snippet: str) -> list[ErrorDetail]:
    """把供应商错误片段转成契约 details（脱敏、截断）。"""
    cleaned = redact(snippet).replace("\n", " ").strip()
    if not cleaned:
        return []
    return [ErrorDetail(field="providerResponse", message=cleaned)]


def _provider_status_of(exc: AiError) -> int | None:
    """从异常的字段级明细里取回供应商 HTTP 状态码（若有）。"""
    for item in exc.details:
        if item.field == "providerStatus":
            try:
                return int(item.message)
            except (TypeError, ValueError):  # pragma: no cover - 防御性
                return None
    return None
