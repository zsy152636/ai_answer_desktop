"""日志与错误脱敏。

约束（契约 §6 安全规则、开发文档 §6.4）：
- 日志（uvicorn access log 之外的自定义 logger）、异常消息、错误响应 message
  一律经过本模块。
- 覆盖键名：authorization / api_key / apikey / token / secret / password /
  credential / cookie 等；命中后输出 `****last4` 形式，不泄漏原值。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any, Final

MASK: Final[str] = "****"
SENSITIVE_KEY_PATTERN: Final[str] = (
    r"authorization|api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|"
    r"token|secret|password|passwd|credential[_-]?ref|credential|cookie|"
    r"set-cookie|proxy-authorization|x-internal-token"
)

# 仅当键“整体”表示敏感信息时脱敏（避免把 maxTokens / usage.tokenCount 误脱敏）
_SENSITIVE_KEY_RE: Final[re.Pattern[str]] = re.compile(
    rf"^(?:{SENSITIVE_KEY_PATTERN})$", re.IGNORECASE
)
# 用于 URL query 与文本型键值对（形如 api_key=xxx / "token": "xxx"）
_KV_RE: Final[re.Pattern[str]] = re.compile(
    rf"(?P<key>[\w\-]*?(?:{SENSITIVE_KEY_PATTERN}))(?P<sep>\s*[:=]\s*)"
    r"(?P<quote>[\"']?)(?P<value>[^\s\"',;&}\]]+)(?P=quote)",
    re.IGNORECASE,
)
# 明文密钥特征：sk-xxx / Bearer xxx
_BEARER_RE: Final[re.Pattern[str]] = re.compile(
    r"\b(?:Bearer|Basic)\s+(?P<value>[A-Za-z0-9\-._~+/=]{4,})", re.IGNORECASE
)
_SK_RE: Final[re.Pattern[str]] = re.compile(r"\bsk-[A-Za-z0-9\-_]{4,}\b")
_AUTH_HEADER_RE: Final[re.Pattern[str]] = re.compile(
    r"(?P<key>authorization)\s*:\s*(?P<value>[^\r\n]+)", re.IGNORECASE
)

_MAX_TEXT_LEN: Final[int] = 2000


def _mask_value(value: str) -> str:
    """`sk-****last4` 形式：保留末 4 位便于比对，其余不可还原。"""
    text = value.strip().strip("\"'")
    if not text:
        return MASK
    if len(text) <= 4:
        return MASK
    return f"{MASK}{text[-4:]}"


def redact(text: Any) -> str:
    """对任意文本做脱敏，返回字符串。"""
    if text is None:
        return ""
    raw = text if isinstance(text, str) else str(text)
    if not raw:
        return raw

    # 1) HTTP 头样式：Authorization: Bearer xxx / "authorization": "xxx"
    raw = _AUTH_HEADER_RE.sub(
        lambda m: f"{m.group('key')}: {_mask_value(m.group('value'))}", raw
    )
    # 2) 其它位置的 Bearer/Basic 凭据
    raw = _BEARER_RE.sub(lambda m: f"Bearer {_mask_value(m.group('value'))}", raw)
    # 3) sk- 前缀明文密钥
    raw = _SK_RE.sub(lambda m: _mask_value(m.group(0)), raw)
    # 4) key=value / "key": "value" 形式
    raw = _KV_RE.sub(
        lambda m: f"{m.group('key')}{m.group('sep')}{m.group('quote')}"
        f"{_mask_value(m.group('value'))}{m.group('quote')}",
        raw,
    )
    if len(raw) > _MAX_TEXT_LEN:
        raw = raw[:_MAX_TEXT_LEN] + "…(truncated)"
    return raw


def redact_mapping(value: Any, _depth: int = 0) -> Any:
    """递归脱敏映射/序列，键名命中敏感模式时整体替换为掩码。"""
    if _depth > 8:
        return MASK
    if isinstance(value, Mapping):
        result: dict[str, Any] = {}
        for key, item in value.items():
            key_text = str(key)
            if _SENSITIVE_KEY_RE.match(key_text):
                result[key_text] = _mask_value(item) if isinstance(item, str) else MASK
            else:
                result[key_text] = redact_mapping(item, _depth + 1)
        return result
    if isinstance(value, (list, tuple, set)):
        items = list(value)
        return [redact_mapping(item, _depth + 1) for item in items]
    if isinstance(value, str):
        return redact(value)
    return value


def safe_repr(value: Any) -> str:
    """异常/日志安全的 repr：先递归脱敏再转字符串。"""
    try:
        return redact(redact_mapping(value))
    except Exception:  # pragma: no cover - 脱敏本身不得让请求失败
        return "<unredactable>"
