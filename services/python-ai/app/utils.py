"""通用小工具（无业务依赖，供各层复用）。"""

from __future__ import annotations

import time
from typing import Final

_MAX_SNIPPET: Final[int] = 400


def monotonic_ms() -> int:
    """单调时钟毫秒值，用于计算耗时。"""
    return int(time.monotonic() * 1000)


def elapsed_ms(started_ms: int) -> int:
    """自 started_ms 起的耗时（非负）。"""
    return max(0, monotonic_ms() - started_ms)


def snippet(text: str | None, limit: int = _MAX_SNIPPET) -> str:
    """截断文本用于错误明细（调用方负责脱敏）。"""
    if not text:
        return ""
    compact = " ".join(text.split())
    return compact[:limit]


__all__ = ["monotonic_ms", "elapsed_ms", "snippet"]
