"""FastAPI 应用装配、启动参数解析、endpoint 文件写出。

启动约束（契约 §0、§7，开发文档 §6.4）：
- 只允许绑定 loopback（127.0.0.0/8、::1、localhost），否则进程直接退出；
- 端口默认 0（随机分配），启动后把实际端口写入 endpoint 文件（原子替换）；
- 内部令牌未显式提供时用 `secrets.token_hex(32)` 生成，只写 endpoint 文件，不进日志；
- 端点信息也可以单行 JSON 交给父进程代为落盘（`--endpoint-stdout`，见下）。
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
import os
import secrets
import sys
from pathlib import Path
from typing import Any, Final

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import __version__
from .api import routes_ai, routes_health
from .config import (
    DEFAULT_HOST,
    DEFAULT_LOG_LEVEL,
    DEFAULT_PORT,
    ENDPOINT_STDOUT_PREFIX,
    ENV_ENDPOINT_FILE,
    ENV_ENDPOINT_STDOUT,
    ENV_HOST,
    ENV_LOG_LEVEL,
    ENV_PORT,
    ENV_TOKEN,
    SERVICE_NAME,
    TOKEN_HEADER,
    Settings,
    generate_internal_token,
    normalize_log_level,
    parse_bool,
    validate_loopback_host,
    validate_port,
)
from .errors import AiError, ErrorCategory, ErrorCode, ErrorDetail
from .schemas import Envelope, new_request_id, utc_now_iso

logger = logging.getLogger("python_ai")

# endpoint 文件写入的最大尝试次数（仅在随机临时名冲突时重试；权限等真实错误立即抛出）
_ENDPOINT_WRITE_ATTEMPTS: Final[int] = 3

# 允许跨源请求的路径前缀：/health 供桌面壳探活，其余接口禁止浏览器跨源调用
_ORIGIN_FREE_PREFIXES: Final[tuple[str, ...]] = ("/health",)


# --------------------------------------------------------------------------------------
# 日志
# --------------------------------------------------------------------------------------


def configure_logging(level: str) -> None:
    """配置自定义 logger（uvicorn access log 之外），输出统一脱敏。"""
    logging.basicConfig(
        level=normalize_log_level(level),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
        force=True,
    )
    # uvicorn 自带 logger 收敛到同一级别
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logging.getLogger(name).setLevel(normalize_log_level(level))


# --------------------------------------------------------------------------------------
# 应用装配
# --------------------------------------------------------------------------------------


def create_app(settings: Settings) -> FastAPI:
    """按配置装配 FastAPI 应用。"""
    app = FastAPI(
        title="AI 智能答题系统 · Python AI 服务",
        version=__version__,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = settings
    app.state.logger = logging.getLogger("python_ai.app")

    app.middleware("http")(_request_context_middleware)
    app.include_router(routes_health.router)
    app.include_router(routes_ai.router)

    _register_exception_handlers(app)
    return app


async def _request_context_middleware(request: Request, call_next: Any) -> Any:
    """生成 requestId、拒绝不可信 Origin，并记录访问日志（脱敏）。"""
    request_id = new_request_id()
    request.state.request_id = request_id
    origin = request.headers.get("origin")
    if origin and not request.url.path.startswith(_ORIGIN_FREE_PREFIXES):
        # 本服务仅供 Java 进程调用；浏览器来源一律拒绝，防跨站请求
        logger.warning(
            "拒绝带 Origin 的请求 path=%s requestId=%s", request.url.path, request_id
        )
        error = AiError(
            code=ErrorCode.ORIGIN_REJECTED,
            message="不接受浏览器跨源请求",
            category=ErrorCategory.BLOCKED,
            http_status=403,
            retryable=False,
        )
        return _error_response(error, request_id)
    response = await call_next(request)
    response.headers["x-request-id"] = request_id
    logger.debug(
        "%s %s -> %s requestId=%s",
        request.method,
        request.url.path,
        response.status_code,
        request_id,
    )
    return response


def _request_id_of(request: Request) -> str:
    request_id = getattr(request.state, "request_id", None)
    return request_id if isinstance(request_id, str) and request_id else new_request_id()


def _error_response(error: AiError, request_id: str) -> JSONResponse:
    envelope = Envelope.fail(error.error_payload(), request_id=request_id)
    return JSONResponse(
        status_code=error.http_status or 500,
        content=envelope.model_dump(mode="json"),
    )


def _register_exception_handlers(app: FastAPI) -> None:
    """统一异常 → 契约 §0.2 失败包络。"""

    @app.exception_handler(AiError)
    async def _handle_ai_error(request: Request, exc: AiError) -> JSONResponse:
        request_id = _request_id_of(request)
        level = logging.WARNING if (exc.http_status or 500) < 500 else logging.ERROR
        logger.log(
            level,
            "AiError code=%s category=%s status=%s path=%s requestId=%s message=%s",
            exc.code.value,
            exc.category.value,
            exc.http_status,
            request.url.path,
            request_id,
            exc.message,
        )
        return _error_response(exc, request_id)

    @app.exception_handler(RequestValidationError)
    async def _handle_validation_error(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        details = [
            ErrorDetail(
                field=".".join(str(part) for part in item.get("loc", ()) if part != "body"),
                message=str(item.get("msg", "invalid")),
            )
            for item in exc.errors()
        ]
        error = AiError.validation("请求参数校验失败", details)
        logger.info(
            "VALIDATION_ERROR path=%s requestId=%s fields=%s",
            request.url.path,
            _request_id_of(request),
            [item.field for item in details],
        )
        return _error_response(error, _request_id_of(request))

    @app.exception_handler(StarletteHTTPException)
    async def _handle_http_exception(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        code = {
            401: ErrorCode.UNAUTHORIZED,
            403: ErrorCode.ORIGIN_REJECTED,
            404: ErrorCode.RESOURCE_NOT_FOUND,
            405: ErrorCode.RESOURCE_NOT_FOUND,
            409: ErrorCode.STATE_CONFLICT,
        }.get(exc.status_code, ErrorCode.INTERNAL_ERROR)
        error = AiError(
            code=code,
            message=str(exc.detail) if exc.detail else "请求失败",
            category=ErrorCategory.PROTOCOL,
            http_status=exc.status_code,
            retryable=False,
        )
        return _error_response(error, _request_id_of(request))

    @app.exception_handler(Exception)
    async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
        request_id = _request_id_of(request)
        from .errors import safe_exception_text

        logger.error(
            "INTERNAL_ERROR path=%s requestId=%s detail=%s",
            request.url.path,
            request_id,
            safe_exception_text(exc),
        )
        return _error_response(AiError.internal(), request_id)


# --------------------------------------------------------------------------------------
# endpoint 文件
# --------------------------------------------------------------------------------------


def build_endpoint_payload(settings: Settings, port: int, pid: int) -> dict[str, Any]:
    """endpoint 文件内容（与需求给出的结构逐字段对齐）。"""
    return {
        "service": SERVICE_NAME,
        "port": port,
        "baseUrl": f"http://127.0.0.1:{port}",
        "token": settings.internal_token,
        "tokenHeader": TOKEN_HEADER,
        "pid": pid,
        "startedAt": utc_now_iso(),
    }


def write_endpoint_file(path: Path, payload: dict[str, Any]) -> None:
    """先写临时文件再原子替换；POSIX 下收紧权限到 0600。

    <p>**刻意不使用 `tempfile.mkstemp`**：在 Windows 上，当目标目录对当前进程不可写时
    （例如子进程完整性级别低于目录标签，或 ACL 受限），`_mkstemp_inner` 会把
    `PermissionError` 当成「同名目录已存在」而 `continue`，对 `os.TMP_MAX`（Windows 上为
    2147483647）个随机文件名逐个重试——表现为进程 100% CPU 空转而永不返回，endpoint 文件
    永远不落盘，父进程与 Java 都拿不到端口（本机已实测复现）。
    这里改成「固定候选名 + 独占创建」并只做有限次重试，任何真实错误都立刻抛出。
    """
    target = path.expanduser()
    target.parent.mkdir(parents=True, exist_ok=True)
    last_conflict: FileExistsError | None = None
    for _ in range(_ENDPOINT_WRITE_ATTEMPTS):
        temp_name = target.with_name(
            f".{target.name}.{os.getpid()}.{secrets.token_hex(4)}.tmp"
        )
        try:
            handle = open(temp_name, "x", encoding="utf-8")
        except FileExistsError as exc:  # 随机名冲突，换一个再试
            last_conflict = exc
            continue
        try:
            with handle:
                json.dump(payload, handle, ensure_ascii=False, indent=2)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            with contextlib.suppress(OSError):  # Windows 上可能不支持 chmod
                os.chmod(temp_name, 0o600)
            os.replace(temp_name, target)
        except BaseException:
            with contextlib.suppress(OSError):
                os.unlink(temp_name)
            raise
        with contextlib.suppress(OSError):
            os.chmod(target, 0o600)
        return
    raise last_conflict if last_conflict is not None else FileExistsError(
        f"无法为 {target} 生成可用的临时文件名"
    )


def endpoint_stdout_line(payload: dict[str, Any]) -> str:
    """把端点信息编码成 stdout 单行，交给父进程代为落盘。

    用途：子进程可能因完整性级别/ACL 限制无法写用户数据目录，而父进程（桌面壳）通常可以。
    只有父进程显式传 `--endpoint-stdout` 时才输出；该行含内部令牌，只走私有管道，
    父进程解析后必须从日志中剔除。
    """
    return ENDPOINT_STDOUT_PREFIX + json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


# --------------------------------------------------------------------------------------
# 启动参数
# --------------------------------------------------------------------------------------


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python-ai",
        description="AI 智能答题系统 · Python AI 服务（FastAPI，仅监听 loopback）",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get(ENV_HOST, DEFAULT_HOST),
        help="监听地址，只允许 127.0.0.1 / ::1 / localhost（默认 127.0.0.1）",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=int(os.environ.get(ENV_PORT, DEFAULT_PORT) or DEFAULT_PORT),
        help="监听端口，0 表示随机端口（默认 0）",
    )
    parser.add_argument(
        "--internal-token",
        default=os.environ.get(ENV_TOKEN, ""),
        help="内部令牌 X-Internal-Token；未提供时随机生成 256bit",
    )
    parser.add_argument(
        "--endpoint-file",
        default=os.environ.get(ENV_ENDPOINT_FILE, ""),
        help="启动成功后写入运行时 endpoint JSON 的路径",
    )
    parser.add_argument(
        "--endpoint-stdout",
        action="store_true",
        default=parse_bool(os.environ.get(ENV_ENDPOINT_STDOUT)),
        help="额外把 endpoint JSON 以单行形式写到 stdout，供父进程代为落盘（含令牌，勿回显）",
    )
    parser.add_argument(
        "--log-level",
        default=os.environ.get(ENV_LOG_LEVEL, DEFAULT_LOG_LEVEL),
        help="日志级别（CRITICAL/ERROR/WARNING/INFO/DEBUG）",
    )
    return parser


def build_settings(argv: list[str] | None = None) -> Settings:
    """解析命令行参数为 Settings；非法参数直接抛 ValueError。"""
    args = build_arg_parser().parse_args(argv)
    host = validate_loopback_host(args.host)
    port = validate_port(args.port)
    token = (args.internal_token or "").strip() or generate_internal_token()
    endpoint_raw = (args.endpoint_file or "").strip()
    return Settings(
        host=host,
        port=port,
        internal_token=token,
        endpoint_file=Path(endpoint_raw) if endpoint_raw else None,
        endpoint_stdout=bool(args.endpoint_stdout),
        log_level=normalize_log_level(args.log_level),
        version=__version__,
    )


# --------------------------------------------------------------------------------------
# 运行
# --------------------------------------------------------------------------------------


async def _serve(settings: Settings) -> None:
    """启动 uvicorn 服务，拿到实际端口后写 endpoint 文件。"""
    import uvicorn

    app = create_app(settings)
    config = uvicorn.Config(
        app,
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level.lower(),
        access_log=False,  # access log 交给自定义 logger（统一脱敏）
        lifespan="on",
    )
    server = uvicorn.Server(config)
    task = asyncio.create_task(server.serve())
    try:
        while not server.started:
            if task.done():
                await task  # 启动失败时抛出原始异常
                return
            await asyncio.sleep(0.05)
        actual_port = _actual_port(server)
        logger.info(
            "python-ai 已启动 baseUrl=http://127.0.0.1:%s providers=%s version=%s",
            actual_port,
            ",".join(settings.providers),
            settings.version,
        )
        logger.info(
            "内部令牌已就绪（脱敏：%s，tokenHeader=%s）",
            settings.display_token,
            TOKEN_HEADER,
        )
        _publish_endpoint(settings, build_endpoint_payload(settings, actual_port, os.getpid()))
        await task
    finally:
        server.should_exit = True
        with contextlib.suppress(asyncio.CancelledError):
            await task


def _publish_endpoint(settings: Settings, payload: dict[str, Any]) -> None:
    """把端点信息交给父进程：优先 stdout 中继，再尽力落盘。

    <p>顺序很重要：先发 stdout，父进程立刻就能拿到端口与令牌；随后的落盘只是为了让
    Java 依旧按文件读取端点。落盘失败（权限/完整性级别受限）只降级为警告，
    绝不阻塞 HTTP 服务，也绝不让进程空转。
    """
    if settings.endpoint_stdout:
        print(endpoint_stdout_line(payload), flush=True)
    if settings.endpoint_file is None:
        return
    try:
        write_endpoint_file(settings.endpoint_file, payload)
    except OSError as exc:
        logger.warning(
            "endpoint 文件写入失败（AI 服务继续可用，将由父进程代为落盘）：%s 原因=%s",
            settings.endpoint_file,
            exc,
        )
    else:
        logger.info("endpoint 文件已写入：%s", settings.endpoint_file)


def _actual_port(server: Any) -> int:
    """从 uvicorn 已绑定的 socket 读取实际端口。"""
    servers = getattr(server, "servers", None) or []
    for candidate in servers:
        for sock in getattr(candidate, "sockets", None) or []:
            try:
                return int(sock.getsockname()[1])
            except (OSError, IndexError):  # pragma: no cover - 极端环境
                continue
    return int(getattr(server.config, "port", 0))  # pragma: no cover


def main(argv: list[str] | None = None) -> int:
    """进程入口：参数校验 → 启动服务。非法参数返回退出码 2。"""
    try:
        settings = build_settings(argv)
    except ValueError as exc:
        print(f"[python-ai] 启动参数非法：{exc}", file=sys.stderr)
        return 2
    configure_logging(settings.log_level)
    if settings.host not in ("127.0.0.1", "::1"):
        logger.warning("绑定地址为 %s（非标准 loopback 字面量）", settings.host)
    try:
        asyncio.run(_serve(settings))
    except KeyboardInterrupt:  # pragma: no cover - 手动中断
        logger.info("收到中断信号，服务已停止")
    return 0


def cli() -> None:  # pragma: no cover - 控制台脚本入口
    raise SystemExit(main())


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
