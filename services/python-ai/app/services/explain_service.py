"""题目讲解（/api/v1/ai/explain）。

阶段 6 增补（契约 §7 与 §11.3）：
- Java 会把本地检索到的资料分块作为 `references` 传入，`content` 是**不可信输入**；
  提示词侧由 `prompts.wrap_untrusted` 做边界转义，这里负责：
  1. 扫描可疑指令模式 → `injectionSuspected` 标记 + **只写脱敏摘要**的日志；
  2. 把模型返回的 `citations` 按「本次实际提供的 refId 集合」过滤，
     未知 / 重复 / 非数组 / 字段缺失一律容错丢弃，绝不因此抛 500。
- 没有 `references` 时：`citations` 恒为空数组、`injectionSuspected` 恒为 false，
  其余行为与阶段 5 完全一致。
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from dataclasses import dataclass, field
from typing import Any, Final

import httpx

from ..errors import AiError, ErrorCategory
from ..providers.base import HttpClientFactory
from ..providers.registry import resolve_provider
from ..schemas import Citation, ExplainRequest, ExplainResponse, Reference
from ..security.injection import scan_references
from ..security.url_guard import IpResolver
from ..utils import elapsed_ms, monotonic_ms
from .json_parse import extract_json_object
from .prompts import EXPLAIN_SYSTEM_PROMPT, build_explain_prompt

logger = logging.getLogger("python_ai.services.explain")

MIN_EXPLANATION_CHARS: int = 10
# 模型返回的 citations 数组元素上限（防病态输出；超出部分直接忽略）
MAX_CITATION_ITEMS: Final[int] = 200
# 裸字符串和无法解析的 used 都按「模型明确引用」处理
_TRUE_USED_VALUES: Final[frozenset[str]] = frozenset(
    {"true", "1", "yes", "y", "是", "used", "引用", "用到了"}
)
_FALSE_USED_VALUES: Final[frozenset[str]] = frozenset(
    {"false", "0", "no", "n", "否", "none", "unused", "未使用", "未引用"}
)
_REF_ID_KEYS: Final[tuple[str, ...]] = ("refId", "ref_id", "refid", "id")


@dataclass(slots=True)
class ExplainOutcome:
    """讲解结果 + 供应商元信息。"""

    response: ExplainResponse
    reasoning_only: bool = False
    raw_tips_ignored: bool = False
    extra: dict[str, Any] = field(default_factory=dict)


async def run_explain(
    request: ExplainRequest,
    *,
    client: httpx.AsyncClient | None = None,
    client_factory: HttpClientFactory | None = None,
    resolver: IpResolver | None = None,
) -> ExplainOutcome:
    """调用模型生成讲解。

    模型可能返回纯文本（不使用 JSON），因此先尝试解析 JSON 结构；
    解析失败或字段缺失时退化为「整段文本即为 explanation」，保证可用性。
    """
    started = monotonic_ms()
    # 注入标记与脱敏日志：只标记，不改写、不丢弃资料（契约 §11.3 第 3 条）
    injection = scan_references(request.references)
    if injection.suspected:
        logger.warning(
            "explain 参考资料疑似提示注入 references=%s %s",
            len(request.references or []),
            injection.redacted_summary(),
        )
    allowed_ref_ids = provided_ref_ids(request.references)
    provider = resolve_provider(
        request.profile, client_factory=client_factory, resolver=resolver
    )
    prompt = build_explain_prompt(request)
    # 思考型模型（qwen3）需要足够大的 max_tokens，否则 content 为空
    result = await provider.complete(
        prompt,
        system=EXPLAIN_SYSTEM_PROMPT,
        client=client,
        max_tokens=request.profile.effective_max_tokens,
    )
    latency_ms = elapsed_ms(started)
    explanation, tips, raw_ignored, raw_citations = _parse_explanation(result.content)
    if len(explanation.strip()) < MIN_EXPLANATION_CHARS:
        # 模型“有输出但内容无效”属于协议问题，不能当作有效讲解返回
        raise AiError.from_category(
            ErrorCategory.PROTOCOL, message="模型返回的讲解内容过短或无效"
        )
    citations = coerce_citations(raw_citations, allowed_ref_ids)
    response = ExplainResponse(
        explanation=explanation,
        tips=tips,
        citations=citations,
        injectionSuspected=injection.suspected,
        model=result.model or request.profile.model,
        usage=result.usage or {},
        latencyMs=latency_ms,
        profileRef=request.profile.profile_ref,
    )
    logger.debug(
        "explain 完成 profileId=%s provider=%s model=%s latencyMs=%s tips=%s citations=%s/%s "
        "reasoningOnly=%s injectionSuspected=%s",
        request.profile.id,
        request.profile.provider,
        response.model,
        response.latency_ms,
        len(response.tips),
        len(response.citations),
        len(allowed_ref_ids),
        result.reasoning_only,
        response.injection_suspected,
    )
    return ExplainOutcome(
        response=response,
        reasoning_only=result.reasoning_only,
        raw_tips_ignored=raw_ignored,
        extra={"httpStatus": result.http_status},
    )


def provided_ref_ids(references: Sequence[Reference] | None) -> list[str]:
    """本次实际提供的 refId（有序去重）——citations 的唯一合法取值来源。"""
    seen: set[str] = set()
    ordered: list[str] = []
    for reference in references or []:
        ref_id = getattr(reference, "ref_id", None)
        if isinstance(ref_id, str) and ref_id and ref_id not in seen:
            seen.add(ref_id)
            ordered.append(ref_id)
    return ordered


def _citation_ref_id(item: Any) -> str | None:
    """从模型返回的元素里取出 refId（裸字符串、对象、别名键都容错）。"""
    if isinstance(item, str):
        text = item.strip()
        return text or None
    if isinstance(item, dict):
        for key in _REF_ID_KEYS:
            value = item.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
            if isinstance(value, int) and not isinstance(value, bool):
                return str(value)
    return None


def _citation_used(item: Any) -> bool:
    """解析 used 标记。

    取舍：模型**主动列出**某个 refId 即表示它要引用该资料，因此
    `used` 缺失或无法解析时按 true 处理（避免丢掉真实引用）；
    只有明确写出 false/0/no/未使用 才视为未使用。
    """
    if isinstance(item, str):
        return True
    if not isinstance(item, dict):
        return False
    value = item.get("used")
    if value is None:
        return True
    if isinstance(value, bool):
        return value
    if isinstance(value, int):
        return value != 0
    if isinstance(value, str):
        text = value.strip().lower()
        if text in _FALSE_USED_VALUES:
            return False
        return True
    return True


def coerce_citations(raw: Any, allowed_ref_ids: Sequence[str]) -> list[Citation]:
    """按「本次提供的 refId 集合」过滤模型返回的 citations（绝不抛异常）。

    容错规则（契约 §11.3：绝不直接信任模型给出的引用）：
    - 未提供任何 references → 恒为空数组，模型说什么都不采信；
    - 非数组 / 元素不是字符串或对象 / 缺少 refId → 丢弃该元素；
    - refId 不在提供集合内 → 丢弃；同一 refId 重复出现 → 只保留首次出现；
    - 保持模型给出的顺序，元素数量上限 `MAX_CITATION_ITEMS`。
    """
    if not allowed_ref_ids or not isinstance(raw, list):
        return []
    allowed = set(allowed_ref_ids)
    seen: set[str] = set()
    citations: list[Citation] = []
    for item in raw[:MAX_CITATION_ITEMS]:
        ref_id = _citation_ref_id(item)
        if not ref_id or ref_id not in allowed or ref_id in seen:
            continue
        seen.add(ref_id)
        citations.append(Citation(ref_id=ref_id, used=_citation_used(item)))
    return citations


def _parse_explanation(content: str) -> tuple[str, list[str], bool, Any]:
    """解析模型输出为 (explanation, tips, 是否忽略了无法识别的结构, 原始 citations)。"""
    text = (content or "").strip()
    if not text:
        return "", [], False, None
    try:
        payload = extract_json_object(text)
    except Exception:
        # 纯文本讲解同样有效
        return text, [], False, None

    explanation = payload.get("explanation")
    if not isinstance(explanation, str) or not explanation.strip():
        # JSON 里没有可用的 explanation：退回全文，避免丢失内容
        return text, _coerce_tips(payload.get("tips")), True, payload.get("citations")
    tips = _coerce_tips(payload.get("tips"))
    return explanation.strip(), tips, False, payload.get("citations")


def _coerce_tips(value: Any) -> list[str]:
    """把 tips 归一化为非空字符串列表（最多 6 条，单条最多 200 字）。"""
    if value is None:
        return []
    if isinstance(value, str):
        candidates: list[Any] = [value]
    elif isinstance(value, list):
        candidates = value
    else:
        return []
    tips: list[str] = []
    for item in candidates:
        if isinstance(item, str) and item.strip():
            tips.append(item.strip()[:200])
        elif isinstance(item, dict):
            text = item.get("text") or item.get("content") or item.get("tip")
            if isinstance(text, str) and text.strip():
                tips.append(text.strip()[:200])
        if len(tips) >= 6:
            break
    return tips
