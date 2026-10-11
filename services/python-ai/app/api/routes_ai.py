"""/api/v1/ai/* 路由。

约定（契约 §0、§7）：
- 除 /health 外全部需要 `X-Internal-Token`（AuthDep）。
- 所有响应使用统一包络（success/data/error/requestId/timestamp），requestId 由
  请求上下文中间件生成，与错误响应保持同一个值。
- test-connection 即使 provider 失败也返回 HTTP 200 + ok=false（供应商故障
  不是本服务的失败）；其余接口的 provider 故障按分类映射契约错误码返回。
"""

from __future__ import annotations

import logging
from typing import Annotated, Any

from fastapi import APIRouter, Path, Query, Request

from ..errors import (
    MESSAGE_BY_CATEGORY,
    SUGGESTION_BY_CATEGORY,
    AiError,
    ErrorCategory,
)
from ..providers.registry import resolve_provider
from ..schemas import (
    Envelope,
    ExplainRequest,
    GradeSubjectiveRequest,
    LlmCallProfile,
    LlmParameters,
    ProbeCapabilities,
    ProbeQuery,
    ProbeResponse,
    TestConnectionRequest,
    TestConnectionResponse,
)
from ..security.auth import AuthDep
from ..services.explain_service import run_explain
from ..services.grading import run_subjective_grading
from ..utils import elapsed_ms, monotonic_ms

logger = logging.getLogger("python_ai.api.ai")

router = APIRouter(prefix="/api/v1/ai", tags=["ai"])


def _ok(data: Any, request_id: str) -> dict[str, Any]:
    """成功包络序列化（data 内字段保持 camelCase）。"""
    return Envelope.ok(data, request_id=request_id).model_dump(mode="json")


def _request_id(request: Request) -> str:
    """复用中间件生成的 requestId（缺失时兜底新生成）。"""
    from ..schemas import new_request_id

    value = getattr(request.state, "request_id", None)
    return value if isinstance(value, str) and value else new_request_id()


@router.post("/explain", summary="题目讲解（需内部令牌）")
async def explain(
    payload: ExplainRequest,
    request: Request,
    _auth: AuthDep,
) -> dict[str, Any]:
    """讲解为什么、指出用户答案错因、给出记忆要点。

    响应含 `citations`（只包含本次提供且被模型引用的 refId）与
    `injectionSuspected`（检索片段命中可疑指令模式的标记，仅标记不改变主流程）。
    """
    request_id = _request_id(request)
    outcome = await run_explain(payload)
    # 日志只允许出现 profileId 与 provider：apiKey 绝不进日志（v1.1 契约 §7）
    logger.info(
        "explain ok profileId=%s provider=%s model=%s latencyMs=%s citations=%s "
        "injectionSuspected=%s requestId=%s",
        payload.profile.id,
        payload.profile.provider,
        outcome.response.model,
        outcome.response.latency_ms,
        len(outcome.response.citations),
        outcome.response.injection_suspected,
        request_id,
    )
    return _ok(outcome.response.model_dump(mode="json", by_alias=True), request_id)


@router.post("/grade-subjective", summary="主观题 AI 建议评分（需内部令牌）")
async def grade_subjective(
    payload: GradeSubjectiveRequest,
    request: Request,
    _auth: AuthDep,
) -> dict[str, Any]:
    """返回建议分与逐条依据；needsReview 恒为 true。"""
    request_id = _request_id(request)
    outcome = await run_subjective_grading(payload)
    # 日志只允许出现 profileId 与 provider：apiKey 绝不进日志（v1.1 契约 §7）
    logger.info(
        "grade-subjective ok profileId=%s provider=%s model=%s score=%s/%s requestId=%s",
        payload.profile.id,
        payload.profile.provider,
        outcome.response.model,
        outcome.response.suggested_score,
        outcome.response.max_score,
        request_id,
    )
    return _ok(outcome.response.model_dump(mode="json", by_alias=True), request_id)


@router.post("/test-connection", summary="模型连接测试（需内部令牌）")
async def test_connection(
    payload: TestConnectionRequest,
    request: Request,
    _auth: AuthDep,
) -> dict[str, Any]:
    """最小化请求 + 短超时；只返回分类与建议，绝不回传请求头或密钥。"""
    request_id = _request_id(request)
    profile = payload.profile
    if payload.api_key:
        profile = profile.model_copy(update={"api_key": payload.api_key})
    provider = resolve_provider(profile)
    started = monotonic_ms()
    try:
        probe = await provider.health_probe()
    except AiError as exc:
        # 连接测试的语义是「报告结果」而不是「请求失败」：地址被安全策略拒绝
        # 同样归一为 blocked 分类结果（分类枚举里就有 blocked）。
        if exc.category is not ErrorCategory.BLOCKED:
            raise
        latency_ms = elapsed_ms(started)
        blocked = TestConnectionResponse(
            ok=False,
            category=ErrorCategory.BLOCKED,
            latencyMs=latency_ms,
            httpStatus=None,
            modelEcho=None,
            message=exc.message,
            suggestion=exc.suggestion
            or SUGGESTION_BY_CATEGORY.get(ErrorCategory.BLOCKED, ""),
        )
        logger.info(
            "test-connection blocked profileId=%s baseUrl=%s requestId=%s",
            profile.id,
            profile.base_url,
            request_id,
        )
        return _ok(blocked.model_dump(mode="json", by_alias=True), request_id)
    latency_ms = elapsed_ms(started)
    ok = probe.reachable and probe.category is ErrorCategory.OK
    response = TestConnectionResponse(
        ok=ok,
        category=probe.category,
        latencyMs=latency_ms,
        httpStatus=probe.http_status,
        modelEcho=_model_echo(probe.models, profile.model),
        message=probe.message or MESSAGE_BY_CATEGORY.get(probe.category, "连接测试失败"),
        suggestion=SUGGESTION_BY_CATEGORY.get(
            probe.category, "检查 baseUrl、模型名称与网络连通性。"
        ),
    )
    logger.info(
        "test-connection ok=%s category=%s profileId=%s latencyMs=%s requestId=%s",
        ok,
        probe.category.value,
        profile.id,
        latency_ms,
        request_id,
    )
    return _ok(response.model_dump(mode="json", by_alias=True), request_id)


def _model_echo(models: list[str], configured: str) -> str | None:
    """模型回显：优先精确匹配，其次首个模型名（便于用户确认名称拼写）。"""
    if configured in models:
        return configured
    if models:
        return models[0]
    return None


@router.get("/profiles/{profile_id}/probe", summary="端点能力探测（不使用密钥）")
async def probe_profile(
    request: Request,
    _auth: AuthDep,
    profile_id: Annotated[str, Path(alias="profile_id", min_length=1, max_length=64)],
    base_url: Annotated[str, Query(alias="baseUrl", min_length=8, max_length=512)],
    provider: Annotated[str, Query()] = "openai-compatible",
    model: Annotated[str | None, Query()] = None,
    timeout_seconds: Annotated[int, Query(alias="timeoutSeconds", ge=1, le=900)] = 10,
    connect_timeout_seconds: Annotated[
        int, Query(alias="connectTimeoutSeconds", ge=1, le=120)
    ] = 5,
    allow_local_network: Annotated[bool, Query(alias="allowLocalNetwork")] = False,
) -> dict[str, Any]:
    """探测 /models（或 /api/tags）是否可用；不携带任何密钥。"""
    request_id = _request_id(request)
    query = ProbeQuery(
        provider=provider,  # type: ignore[arg-type]
        baseUrl=base_url,
        model=model,
        timeoutSeconds=timeout_seconds,
        connectTimeoutSeconds=connect_timeout_seconds,
        allowLocalNetwork=allow_local_network,
    )
    # 探测不使用密钥：显式清空 apiKey（契约要求“无密钥也能探测则 reachable=true”）
    profile = LlmCallProfile(
        id=profile_id,
        provider=query.provider,
        baseUrl=query.base_url,
        model=query.model or "probe",
        timeoutSeconds=query.timeout_seconds,
        connectTimeoutSeconds=query.connect_timeout_seconds,
        allowLocalNetwork=query.allow_local_network,
        parameters=LlmParameters(),
        apiKey=None,
    )
    probe = await resolve_provider(profile).health_probe()
    response = ProbeResponse(
        profileId=profile_id,
        reachable=probe.reachable,
        models=probe.models,
        capabilities=ProbeCapabilities(
            chatCompletions=probe.chat_completions,
            modelsEndpoint=probe.models_endpoint,
        ),
    )
    logger.info(
        "probe profileId=%s provider=%s reachable=%s models=%s requestId=%s",
        profile_id,
        query.provider,
        probe.reachable,
        len(probe.models),
        request_id,
    )
    return _ok(response.model_dump(mode="json", by_alias=True), request_id)


__all__ = ["router", "AiError"]
