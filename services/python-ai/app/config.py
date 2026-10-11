"""运行配置：端口、内部令牌、endpoint 文件路径、日志级别。

设计决策：
- 配置对象不可变（frozen dataclass），避免运行期被意外修改。
- 只从命令行参数与环境变量读取（main.py 负责解析），本模块不依赖 argparse。
- 只允许绑定 loopback，校验逻辑放在 `validate_loopback_host`，main.py 启动前调用。
"""

from __future__ import annotations

import ipaddress
import os
import secrets
from dataclasses import dataclass, field
from pathlib import Path
from typing import Final

SERVICE_NAME: Final[str] = "python-ai"
SERVICE_VERSION: Final[str] = "1.0.0"
TOKEN_HEADER: Final[str] = "X-Internal-Token"

DEFAULT_HOST: Final[str] = "127.0.0.1"
DEFAULT_PORT: Final[int] = 0  # 0 = 随机可用端口
DEFAULT_LOG_LEVEL: Final[str] = "INFO"

# 环境变量名（与 README 保持一致）
ENV_HOST: Final[str] = "PY_AI_HOST"
ENV_PORT: Final[str] = "PY_AI_PORT"
ENV_TOKEN: Final[str] = "PY_AI_INTERNAL_TOKEN"
ENV_ENDPOINT_FILE: Final[str] = "PY_AI_ENDPOINT_FILE"
ENV_ENDPOINT_STDOUT: Final[str] = "PY_AI_ENDPOINT_STDOUT"
ENV_LOG_LEVEL: Final[str] = "PY_AI_LOG_LEVEL"

# 端点信息经 stdout 交给父进程时的行前缀（必须与桌面壳
# apps/desktop/src/main/service-manager.ts 的 PYTHON_ENDPOINT_STDOUT_PREFIX 完全一致）。
# 该行含内部令牌，父进程解析后不得回显到日志。
ENDPOINT_STDOUT_PREFIX: Final[str] = "AIA_ENDPOINT "

_TRUTHY: Final[frozenset[str]] = frozenset({"1", "true", "yes", "on"})

# 供应商名单：v1 仅实现这两个（契约 §7 与 llm-config schema 白名单一致）
SUPPORTED_PROVIDERS: Final[tuple[str, ...]] = ("openai-compatible", "ollama")

_LOG_LEVELS: Final[frozenset[str]] = frozenset(
    {"CRITICAL", "ERROR", "WARNING", "INFO", "DEBUG"}
)


@dataclass(frozen=True, slots=True)
class Settings:
    """运行期配置。"""

    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    internal_token: str = ""
    endpoint_file: Path | None = None
    endpoint_stdout: bool = False
    log_level: str = DEFAULT_LOG_LEVEL
    version: str = SERVICE_VERSION
    providers: tuple[str, ...] = field(default=SUPPORTED_PROVIDERS)

    @property
    def display_token(self) -> str:
        """令牌的脱敏展示形式，绝不返回完整令牌。"""
        token = self.internal_token
        if len(token) <= 4:
            return "****"
        return f"****{token[-4:]}"


def generate_internal_token() -> str:
    """生成 256bit 内部令牌（64 位 hex）。"""
    return secrets.token_hex(32)


def normalize_log_level(value: str | None) -> str:
    """归一化日志级别；非法值回退到 INFO。"""
    if not value:
        return DEFAULT_LOG_LEVEL
    upper = value.strip().upper()
    return upper if upper in _LOG_LEVELS else DEFAULT_LOG_LEVEL


def parse_bool(value: str | None) -> bool:
    """宽松解析布尔开关（环境变量用）；空值视为 False。"""
    return bool(value) and value.strip().lower() in _TRUTHY


def validate_loopback_host(host: str) -> str:
    """硬校验绑定地址必须是 loopback 字面量。

    只接受 `127.0.0.0/8` 内的 IPv4、`::1` 与 `localhost`；其余（含 `0.0.0.0`、
    主机名、内网 IP）直接抛错，由 main.py 转成进程退出。
    """
    candidate = (host or "").strip()
    if not candidate:
        raise ValueError("host 不能为空")
    if candidate.lower() == "localhost":
        return DEFAULT_HOST
    try:
        ip = ipaddress.ip_address(candidate)
    except ValueError as exc:
        raise ValueError(
            f"host 必须是 loopback 字面量（127.0.0.1 / ::1 / localhost），收到：{candidate!r}"
        ) from exc
    if not ip.is_loopback:
        raise ValueError(f"host 只允许 loopback，收到：{candidate!r}")
    return str(ip)


def validate_port(port: int) -> int:
    """端口范围校验：0 表示随机分配。"""
    if not isinstance(port, int) or isinstance(port, bool):
        raise ValueError("port 必须是整数")
    if port < 0 or port > 65535:
        raise ValueError(f"port 超出范围：{port}")
    return port


def load_settings_from_env(environ: dict[str, str] | None = None) -> Settings:
    """从环境变量构造配置（命令行参数优先级更高，由 main.py 覆盖）。"""
    env = os.environ if environ is None else environ
    raw_port = env.get(ENV_PORT, "")
    port = int(raw_port) if raw_port.strip().isdigit() else DEFAULT_PORT
    raw_endpoint = env.get(ENV_ENDPOINT_FILE, "").strip()
    return Settings(
        host=validate_loopback_host(env.get(ENV_HOST, DEFAULT_HOST)),
        port=validate_port(port),
        internal_token=env.get(ENV_TOKEN, "").strip(),
        endpoint_file=Path(raw_endpoint) if raw_endpoint else None,
        endpoint_stdout=parse_bool(env.get(ENV_ENDPOINT_STDOUT)),
        log_level=normalize_log_level(env.get(ENV_LOG_LEVEL)),
    )
