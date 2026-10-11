"""内部令牌鉴权依赖（契约 §0：除 /health 外所有请求必须带 X-Internal-Token）。

设计决策：
- 令牌来自应用启动配置（app.state.settings），不落盘、不进日志。
- 比较使用 `secrets.compare_digest`，避免时序侧信道。
- 失败统一抛 `AiError(UNAUTHORIZED)`，由全局异常处理器输出契约错误包络。
"""

from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Depends, Header, Request

from ..config import TOKEN_HEADER, Settings
from ..errors import AiError


def get_settings(request: Request) -> Settings:
    """从应用状态读取运行配置。"""
    settings: Settings | None = getattr(request.app.state, "settings", None)
    if settings is None:  # pragma: no cover - 装配错误
        raise AiError.internal("服务未完成初始化")
    return settings


SettingsDep = Annotated[Settings, Depends(get_settings)]


def require_internal_token(
    request: Request,
    settings: SettingsDep,
    x_internal_token: Annotated[str | None, Header(alias=TOKEN_HEADER)] = None,
) -> None:
    """校验内部令牌；缺失/错误 → 401 UNAUTHORIZED。"""
    provided = (x_internal_token or "").strip()
    expected = settings.internal_token
    if not expected:  # pragma: no cover - 启动时必然生成
        raise AiError.internal("服务内部令牌未初始化")
    if not provided or not secrets.compare_digest(provided, expected):
        raise AiError.unauthorised("内部令牌缺失或无效")
    # 令牌本身不写入日志，仅在 debug 级别记录“校验通过”
    request.app.state.logger.debug("内部令牌校验通过 path=%s", request.url.path)


AuthDep = Annotated[None, Depends(require_internal_token)]
