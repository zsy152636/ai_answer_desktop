"""/health 路由（契约 §7：不鉴权、不参与版本前缀）。"""

from __future__ import annotations

from fastapi import APIRouter

from ..config import SERVICE_NAME, SERVICE_VERSION
from ..schemas import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse, summary="健康检查（不鉴权）")
async def health() -> HealthResponse:
    """返回服务状态与已注册 provider（与契约 §7 完全一致）。"""
    from ..providers.registry import supported_providers

    return HealthResponse(
        status="UP",
        service=SERVICE_NAME,
        version=SERVICE_VERSION,
        providers=supported_providers(),
    )
