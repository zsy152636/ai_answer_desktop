"""主观题 AI 评分（/api/v1/ai/grade-subjective）。

关键约束（契约 §7 + 需求）：
- 模型必须返回严格 JSON；解析容错由 json_parse 负责，解析失败归 `protocol`。
- `suggestedScore` 必须夹取到 `[0, maxScore]`。
- `criteria` 逐项分数不得超过该细则对应分值（按权重换算）。
- `needsReview` 恒为 true：AI 分数只是建议分，最终由人工复核。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

import httpx

from ..errors import AiError, ErrorCategory
from ..providers.base import HttpClientFactory
from ..providers.registry import resolve_provider
from ..schemas import (
    GradeCriterionResult,
    GradeSubjectiveRequest,
    GradeSubjectiveResponse,
    RubricCriterion,
)
from ..security.url_guard import IpResolver
from ..utils import elapsed_ms, monotonic_ms
from .json_parse import extract_json_object
from .prompts import GRADE_SYSTEM_PROMPT, build_grade_prompt

logger = logging.getLogger("python_ai.services.grading")

DEFAULT_CONFIDENCE: float = 0.5
MAX_EXTRA_CRITERIA: int = 50


@dataclass(slots=True)
class GradingOutcome:
    """评分结果 + 供日志/审计使用的元信息。"""

    response: GradeSubjectiveResponse
    reasoning_only: bool = False
    criterion_maxima: dict[str, float] = field(default_factory=dict)
    model_reported_score: float | None = None
    clamped: bool = False


async def run_subjective_grading(
    request: GradeSubjectiveRequest,
    *,
    client: httpx.AsyncClient | None = None,
    client_factory: HttpClientFactory | None = None,
    resolver: IpResolver | None = None,
) -> GradingOutcome:
    """调用模型评分并做服务端校验/夹取。"""
    started = monotonic_ms()
    provider = resolve_provider(
        request.profile, client_factory=client_factory, resolver=resolver
    )
    prompt = build_grade_prompt(request)
    result = await provider.complete(
        prompt,
        system=GRADE_SYSTEM_PROMPT,
        client=client,
        max_tokens=request.profile.effective_max_tokens,
        json_mode=True,
    )
    latency_ms = elapsed_ms(started)
    payload = extract_json_object(result.content)
    maxima = compute_criterion_maxima(request.question.rubric, request.max_score)
    criteria, clamped = _build_criteria(payload, maxima, request.max_score)
    suggested = _coerce_score(payload.get("suggestedScore"))
    if suggested is None:
        suggested = round(sum(item.score for item in criteria), 2)
    bounded = _clamp(suggested, 0.0, request.max_score)
    if abs(bounded - suggested) > 1e-9:
        clamped = True
    confidence = _coerce_confidence(payload.get("confidence"), len(criteria))
    response = GradeSubjectiveResponse(
        suggestedScore=round(bounded, 2),
        maxScore=request.max_score,
        criteria=criteria,
        confidence=confidence,
        needsReview=True,
        model=result.model or request.profile.model,
        latencyMs=latency_ms,
        profileRef=request.profile.profile_ref,
    )
    logger.debug(
        "grade 完成 profileId=%s provider=%s model=%s criteria=%s suggested=%s/%s "
        "clamped=%s latencyMs=%s",
        request.profile.id,
        request.profile.provider,
        response.model,
        len(criteria),
        response.suggested_score,
        response.max_score,
        clamped,
        response.latency_ms,
    )
    return GradingOutcome(
        response=response,
        reasoning_only=result.reasoning_only,
        criterion_maxima=maxima,
        model_reported_score=suggested,
        clamped=clamped,
    )


def compute_criterion_maxima(
    rubric: Any, max_score: float
) -> dict[str, float]:
    """计算每条评分细则的分值上限。

    规则（对常见 rubric 约定都成立）：
    - 权重合计为 100 → 视为百分制权重：`maxScore × weight / 100`；
    - 否则按权重比例归一化到 `maxScore`；
    - 权重全为 0 或缺失 → 在细则之间均分。
    """
    criteria: list[RubricCriterion] = list(getattr(rubric, "criteria", []) or [])
    if not criteria:
        return {}
    weights = [max(0.0, float(item.weight or 0.0)) for item in criteria]
    total = sum(weights)
    result: dict[str, float] = {}
    if total <= 0:
        each = round(max_score / len(criteria), 4)
        for item in criteria:
            result[item.id] = each
        return result
    if abs(total - 100.0) < 1e-6:
        for item, weight in zip(criteria, weights, strict=True):
            result[item.id] = round(max_score * weight / 100.0, 4)
        return result
    for item, weight in zip(criteria, weights, strict=True):
        result[item.id] = round(max_score * weight / total, 4)
    return result


def _build_criteria(
    payload: dict[str, Any], maxima: dict[str, float], max_score: float
) -> tuple[list[GradeCriterionResult], bool]:
    """把模型返回的 criteria 逐项夹取到其分值上限。"""
    raw_items = payload.get("criteria")
    if not isinstance(raw_items, list):
        raise AiError.from_category(
            ErrorCategory.PROTOCOL, message="模型返回内容缺少 criteria 数组"
        )
    results: list[GradeCriterionResult] = []
    clamped = False
    seen: set[str] = set()
    for index, item in enumerate(raw_items[:MAX_EXTRA_CRITERIA]):
        if not isinstance(item, dict):
            raise AiError.from_category(
                ErrorCategory.PROTOCOL, message="criteria 元素不是 JSON 对象"
            )
        criterion_id = item.get("id")
        if not isinstance(criterion_id, str) or not criterion_id.strip():
            criterion_id = f"extra-{index + 1}"
        criterion_id = criterion_id.strip()
        if criterion_id in seen:
            criterion_id = f"{criterion_id}-{index + 1}"
        seen.add(criterion_id)
        upper = maxima.get(criterion_id)
        raw_score = _coerce_score(item.get("score"))
        if raw_score is None:
            raw_score = 0.0
        if upper is None:
            # 模型臆造的细则：不给分值空间，只记录说明
            score = 0.0
            if raw_score != 0.0:
                clamped = True
        else:
            score = _clamp(raw_score, 0.0, upper)
            if abs(score - raw_score) > 1e-9:
                clamped = True
        reason = item.get("reason")
        results.append(
            GradeCriterionResult(
                id=criterion_id,
                score=round(score, 2),
                reason=reason.strip()[:500] if isinstance(reason, str) else "",
            )
        )
    # 细则缺失时补 0 分项，保证 criteria 与 rubric 对齐（便于人工复核逐条核对）
    for criterion_id in maxima:
        if criterion_id not in seen:
            results.append(
                GradeCriterionResult(
                    id=criterion_id, score=0.0, reason="模型未返回该评分细则，按 0 分待人工复核"
                )
            )
    # 逐项分数合计不得超过满分：超出时整体等比压缩（保持相对差异，可解释）
    total = sum(item.score for item in results)
    if total > max_score and total > 0:
        ratio = max_score / total
        for item in results:
            item.score = round(item.score * ratio, 2)
        clamped = True
    return results, clamped


def _coerce_score(value: Any) -> float | None:
    """把模型返回的分数转成 float；无法识别返回 None。"""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        text = value.strip().replace("分", "").replace("%", "")
        try:
            return float(text)
        except ValueError:
            return None
    return None


def _coerce_confidence(value: Any, criteria_count: int) -> float:
    """置信度夹取到 [0,1]；缺失时给保守默认值。"""
    parsed = _coerce_score(value)
    if parsed is None:
        return DEFAULT_CONFIDENCE if criteria_count else 0.3
    return round(_clamp(parsed, 0.0, 1.0), 4)


def _clamp(value: float, lower: float, upper: float) -> float:
    if upper < lower:  # pragma: no cover - 防御性
        return lower
    return max(lower, min(upper, value))
