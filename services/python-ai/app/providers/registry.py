"""Provider 白名单解析（契约 §7 + llm-config.schema.json 的 provider 枚举）。"""

from __future__ import annotations

import logging
from typing import Final

from ..errors import AiError, ErrorCategory, ErrorCode
from ..schemas import LlmCallProfile
from ..security.url_guard import IpResolver
from .base import HttpClientFactory, LlmProvider
from .ollama import OllamaProvider
from .openai_compatible import OpenAiCompatibleProvider

logger = logging.getLogger("python_ai.providers.registry")

# v1 实现的两个 provider；anthropic / azure-openai 为预留（明确报协议错误）
SUPPORTED_PROVIDER_NAMES: Final[tuple[str, ...]] = ("openai-compatible", "ollama")
RESERVED_PROVIDER_NAMES: Final[tuple[str, ...]] = ("anthropic", "azure-openai")

_PROVIDER_TYPES: Final[dict[str, type[LlmProvider]]] = {
    "openai-compatible": OpenAiCompatibleProvider,
    "ollama": OllamaProvider,
}


def supported_providers() -> list[str]:
    """注册表里的 provider 名称（用于 /health）。"""
    return list(SUPPORTED_PROVIDER_NAMES)


def resolve_provider(
    profile: LlmCallProfile,
    *,
    client_factory: HttpClientFactory | None = None,
    resolver: IpResolver | None = None,
) -> LlmProvider:
    """按 profile.provider 构造 provider；未实现/未知 provider 直接报错。"""
    name = (profile.provider or "").strip().lower()
    provider_type = _PROVIDER_TYPES.get(name)
    if provider_type is None:
        if name in RESERVED_PROVIDER_NAMES:
            raise AiError(
                code=ErrorCode.LLM_UNAVAILABLE,
                message=f"provider {name} 尚未实现（v1 仅支持 openai-compatible / ollama）",
                category=ErrorCategory.PROTOCOL,
                http_status=400,
                retryable=False,
            )
        raise AiError(
            code=ErrorCode.VALIDATION_ERROR,
            message=f"不支持的 provider：{name or '(空)'}",
            category=ErrorCategory.PROTOCOL,
            http_status=400,
            retryable=False,
        )
    provider = provider_type(profile, client_factory=client_factory, resolver=resolver)
    logger.debug("resolve_provider name=%s profileId=%s", name, profile.id)
    return provider
