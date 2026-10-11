"""Ollama 原生端点适配器（`/api/chat`、`/api/tags`）。

说明：契约里 Ollama 也提供 OpenAI 兼容层（`{baseUrl}/v1`），本适配器使用
Ollama 原生协议，便于在未开启兼容层时也能探测与调用。
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import Any, Final

import httpx

from ..errors import AiError, ErrorCategory
from .base import ChatMessage, ChatResult, EndpointProbe, LlmProvider

logger = logging.getLogger("python_ai.providers.ollama")

CHAT_PATH: Final[str] = "/api/chat"
TAGS_PATH: Final[str] = "/api/tags"


class OllamaProvider(LlmProvider):
    """Ollama 原生协议适配器。"""

    name = "ollama"

    def chat_path(self) -> str:
        return CHAT_PATH

    def probe_path(self) -> str:
        return TAGS_PATH

    def parse_models(self, payload: Any) -> list[str]:
        if not isinstance(payload, dict):
            return []
        items = payload.get("models")
        if not isinstance(items, list):
            return []
        names: list[str] = []
        for item in items:
            if not isinstance(item, dict):
                continue
            candidate = item.get("name") or item.get("model")
            if isinstance(candidate, str) and candidate and candidate not in names:
                names.append(candidate)
        return names

    def build_payload(
        self,
        messages: Sequence[ChatMessage],
        *,
        max_tokens: int | None,
        json_mode: bool,
    ) -> dict[str, Any]:
        options: dict[str, Any] = {}
        params = self.profile.parameters
        if params.temperature is not None:
            options["temperature"] = params.temperature
        if params.top_p is not None:
            options["top_p"] = params.top_p
        # Ollama 用 num_predict 表示最大生成 token 数
        options["num_predict"] = max_tokens or self.profile.effective_max_tokens
        payload: dict[str, Any] = {
            "model": self.profile.model,
            "messages": [message.to_dict() for message in messages],
            "stream": False,
            "options": options,
        }
        if json_mode:
            payload["format"] = "json"
        return payload

    async def chat(
        self,
        messages: Sequence[ChatMessage],
        *,
        client: httpx.AsyncClient | None = None,
        max_tokens: int | None = None,
        json_mode: bool = False,
    ) -> ChatResult:
        url = self.build_url(CHAT_PATH)
        payload = self.build_payload(
            messages, max_tokens=max_tokens, json_mode=json_mode
        )
        status, body, _headers, latency_ms = await self._request(
            "POST", url, json_body=payload, client=client
        )
        if not isinstance(body, dict):
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="Ollama 返回结构不是 JSON 对象"
            )
        if body.get("error"):
            raise AiError.from_category(
                ErrorCategory.PROTOCOL,
                message="Ollama 返回错误字段（协议不兼容或模型异常）",
            )
        message = body.get("message")
        if not isinstance(message, dict):
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="Ollama 返回缺少 message 字段"
            )
        content, fallback = self.extract_content(message)
        usage = {
            "promptTokens": body.get("prompt_eval_count"),
            "completionTokens": body.get("eval_count"),
        }
        return ChatResult(
            content=content,
            model=str(body.get("model") or self.profile.model),
            usage={key: value for key, value in usage.items() if value is not None},
            latency_ms=latency_ms,
            http_status=status,
            reasoning_only=fallback,
        )

    async def health_probe(
        self, *, client: httpx.AsyncClient | None = None
    ) -> EndpointProbe:
        """最小化请求：`GET {baseUrl}/api/tags`。"""
        url = self.build_url(TAGS_PATH)
        outcome = await self.fetch_raw("GET", url, client=client)
        models = self.parse_models(outcome.payload) if outcome.ok else []
        # 端点有响应（含 401/429 等）即视为可达，只是本次探测拿不到模型列表
        reachable = outcome.ok or outcome.status is not None
        if outcome.ok:
            message = f"端点可达，返回 {len(models)} 个模型"
        else:
            message = outcome.message or "端点不可达"
        logger.debug(
            "probe ollama /api/tags status=%s category=%s modelCount=%s",
            outcome.status,
            outcome.category.value,
            len(models),
        )
        return EndpointProbe(
            reachable=reachable,
            models=models if outcome.ok else [],
            chat_completions=reachable,
            models_endpoint=reachable,
            http_status=outcome.status,
            category=outcome.category,
            message=message,
        )


__all__ = ["OllamaProvider", "CHAT_PATH", "TAGS_PATH"]
