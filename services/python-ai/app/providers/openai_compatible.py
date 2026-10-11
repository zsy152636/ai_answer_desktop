"""OpenAI 兼容供应商适配器（含 OpenAI、vLLM、Ollama 的 /v1 兼容层、LM Studio 等）。"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import Any, Final

import httpx

from ..errors import AiError, ErrorCategory
from .base import (
    ChatMessage,
    ChatResult,
    EndpointProbe,
    LlmProvider,
)

logger = logging.getLogger("python_ai.providers.openai_compatible")

CHAT_PATH: Final[str] = "/chat/completions"
MODELS_PATH: Final[str] = "/models"


class OpenAiCompatibleProvider(LlmProvider):
    """`{baseUrl}/chat/completions` 协议。baseUrl 形如 http://host:port/v1。"""

    name = "openai-compatible"

    def chat_path(self) -> str:
        return CHAT_PATH

    def probe_path(self) -> str:
        return MODELS_PATH

    def parse_models(self, payload: Any) -> list[str]:
        if not isinstance(payload, dict):
            return []
        items = payload.get("data")
        if not isinstance(items, list):
            return []
        names: list[str] = []
        for item in items:
            if isinstance(item, dict):
                candidate = item.get("id") or item.get("name")
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
        payload: dict[str, Any] = {
            "model": self.profile.model,
            "messages": [message.to_dict() for message in messages],
            "stream": False,
            # 思考型模型（qwen3 等）需要足够的 max_tokens，否则 content 为空
            "max_tokens": max_tokens or self.profile.effective_max_tokens,
        }
        payload.update(self.profile.parameters.to_api_kwargs())
        if json_mode:
            # 显式指定 JSON 输出；若供应商不识别也不会破坏协议
            payload["response_format"] = {"type": "json_object"}
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
        message = self._extract_choice(body)
        content, fallback = self.extract_content(message)
        usage = body.get("usage") if isinstance(body, dict) else None
        model = body.get("model") if isinstance(body, dict) else None
        return ChatResult(
            content=content,
            model=str(model or self.profile.model),
            usage=usage if isinstance(usage, dict) else {},
            latency_ms=latency_ms,
            http_status=status,
            reasoning_only=fallback,
        )

    @staticmethod
    def _extract_choice(body: Any) -> dict[str, Any]:
        """从 /chat/completions 响应中取出 choices[0].message。"""
        if not isinstance(body, dict):
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="模型服务返回结构不是 JSON 对象"
            )
        choices = body.get("choices")
        if not isinstance(choices, list) or not choices:
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="模型服务返回缺少 choices 字段"
            )
        first = choices[0]
        if not isinstance(first, dict):
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="模型服务返回的 choices 结构非法"
            )
        message = first.get("message")
        if message is None and isinstance(first.get("delta"), dict):
            message = first["delta"]
        if not isinstance(message, dict):
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="模型服务返回缺少 message 字段"
            )
        return message

    async def health_probe(
        self, *, client: httpx.AsyncClient | None = None
    ) -> EndpointProbe:
        """最小化请求：`GET {baseUrl}/models`（不带密钥也应能判断可达性）。"""
        url = self.build_url(MODELS_PATH)
        outcome = await self.fetch_raw("GET", url, client=client)
        models = self.parse_models(outcome.payload) if outcome.ok else []
        reachable = outcome.ok or (
            outcome.status is not None
            and outcome.category
            in (
                ErrorCategory.AUTH,
                ErrorCategory.QUOTA,
                ErrorCategory.PROTOCOL,
                ErrorCategory.MODEL_NOT_FOUND,
            )
        )
        if outcome.ok:
            message = f"端点可达，返回 {len(models)} 个模型"
        else:
            message = outcome.message or "端点不可达"
        logger.debug(
            "probe openai-compatible /models status=%s category=%s modelCount=%s",
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


__all__ = ["OpenAiCompatibleProvider", "CHAT_PATH", "MODELS_PATH"]
