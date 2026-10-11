"""从大模型回复中容错地抽取 JSON（契约 §7 grade-subjective 要求）。

覆盖的常见情况：
1. 前后带 ```json 围栏；
2. 前后带解释性文字；
3. JSON 之前有思考文本、之后有总结文本；
4. 字符串里含 `{`/`}` 或转义引号。

抽取失败 → `protocol` 分类错误（绝不把模型原始全文透传给上层）。
"""

from __future__ import annotations

import json
import logging
from typing import Any

from ..errors import AiError, ErrorCategory

logger = logging.getLogger("python_ai.services.json_parse")

_FENCE_TOKENS: tuple[str, ...] = ("```json", "```JSON", "```")


def extract_json_object(text: str) -> dict[str, Any]:
    """从文本中抽取第一个合法的 JSON 对象。"""
    if not text or not text.strip():
        raise _protocol_error("模型返回内容为空")
    raw = text.strip()

    for candidate in _fence_candidates(raw):
        parsed = _try_object(candidate)
        if parsed is not None:
            return parsed
    parsed = _try_object(raw)
    if parsed is not None:
        return parsed
    for candidate in _balanced_object_candidates(raw):
        parsed = _try_object(candidate)
        if parsed is not None:
            return parsed
    raise _protocol_error("无法从模型返回内容中解析出 JSON 对象")


def _fence_candidates(text: str) -> list[str]:
    """提取 ```json ... ``` 围栏内容。"""
    candidates: list[str] = []
    for token in _FENCE_TOKENS:
        start = text.find(token)
        while start != -1:
            body_start = start + len(token)
            end = text.find("```", body_start)
            body = text[body_start:end] if end != -1 else text[body_start:]
            body = body.strip()
            if body:
                candidates.append(body)
            if end == -1:
                break
            start = text.find(token, end + 3)
    return candidates


def _try_object(text: str) -> dict[str, Any] | None:
    """严格解析；失败返回 None。"""
    candidate = text.strip()
    if not candidate or not candidate.startswith("{"):
        return None
    try:
        parsed = json.loads(candidate)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def _balanced_object_candidates(text: str) -> list[str]:
    """扫描所有花括号平衡的片段（忽略字符串内的括号）。"""
    candidates: list[str] = []
    depth = 0
    start = -1
    in_string = False
    escaped = False
    for index, char in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
            continue
        if char == "{":
            if depth == 0:
                start = index
            depth += 1
        elif char == "}":
            if depth > 0:
                depth -= 1
                if depth == 0 and start != -1:
                    candidates.append(text[start : index + 1])
                    start = -1
    # 最外层片段优先（通常就是模型输出的主体）
    candidates.sort(key=len, reverse=True)
    return candidates


def _protocol_error(message: str) -> AiError:
    return AiError.from_category(ErrorCategory.PROTOCOL, message=message)


__all__ = ["extract_json_object"]
