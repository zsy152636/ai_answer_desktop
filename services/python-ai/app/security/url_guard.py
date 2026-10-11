"""baseUrl 安全校验（开发文档 §6.4、契约 §6/§7）。

每次调用（含每一跳重定向）都必须校验：
1. 协议只允许 http/https；拒绝带凭据的 URL；拒绝非法端口。
2. 用 `socket.getaddrinfo` 解析全部 IP，逐条判定。
3. `allowLocalNetwork=false` 时拒绝 loopback、RFC1918 私网、链路本地
   （169.254.0.0/16、fe80::/10）、唯一本地 fc00::/7、云元数据
   （169.254.169.254、100.100.100.200）→ 分类 `blocked`。
4. 解析结果带短 TTL 缓存，供 httpx 连接阶段复用，缩小 DNS 重绑定窗口。

HTTP 客户端一律 `follow_redirects=False`，由 `guarded_request` 逐跳重新校验。
"""

from __future__ import annotations

import ipaddress
import logging
import socket
import threading
import time
from dataclasses import dataclass, field
from typing import Final
from urllib.parse import urlsplit

import httpx

from ..errors import AiError, ErrorCategory, ErrorCode
from .redaction import redact

logger = logging.getLogger("python_ai.url_guard")

ALLOWED_SCHEMES: Final[frozenset[str]] = frozenset({"http", "https"})
CLOUD_METADATA_IPS: Final[frozenset[str]] = frozenset(
    {"169.254.169.254", "100.100.100.200", "fd00:ec2::254"}
)
MAX_REDIRECTS: Final[int] = 3
DNS_CACHE_TTL_SECONDS: Final[float] = 5.0
_REDIRECT_STATUSES: Final[frozenset[int]] = frozenset({301, 302, 303, 307, 308})

_ULA_V6: Final[ipaddress.IPv6Network] = ipaddress.IPv6Network("fc00::/7")


class UrlBlockedError(AiError):
    """地址被安全策略拒绝（契约错误码 BASE_URL_BLOCKED / 分类 blocked）。"""

    def __init__(self, message: str) -> None:
        super().__init__(
            code=ErrorCode.BASE_URL_BLOCKED,
            message=message,
            category=ErrorCategory.BLOCKED,
            http_status=400,
            retryable=False,
            suggestion=(
                "该地址属于 loopback/私网/链路本地/云元数据，"
                "需显式开启“允许本地网络”或改用公网端点。"
            ),
        )


@dataclass(frozen=True, slots=True)
class UrlVerdict:
    """校验结果。"""

    url: str
    scheme: str
    host: str
    port: int
    resolved_ips: tuple[str, ...] = field(default=())


@dataclass(frozen=True, slots=True)
class _CacheEntry:
    ips: tuple[str, ...]
    expires_at: float


class IpResolver:
    """带 TTL 缓存的 DNS 解析器（进程内，线程安全）。

    缓存的目的不是性能，而是让「校验时解析的 IP」与「连接时解析的 IP」
    来自同一批次结果，降低 DNS 重绑定风险；TTL 保持很短。
    """

    def __init__(self, ttl_seconds: float = DNS_CACHE_TTL_SECONDS) -> None:
        self._ttl = ttl_seconds
        self._cache: dict[str, _CacheEntry] = {}
        self._lock = threading.Lock()

    def resolve(self, host: str, port: int) -> tuple[str, ...]:
        """解析主机名到全部 IP（含 IPv4/IPv6）；解析失败抛 `dns` 分类异常。"""
        cache_key = f"{host}:{port}"
        now = time.monotonic()
        with self._lock:
            entry = self._cache.get(cache_key)
            if entry is not None and entry.expires_at > now:
                return entry.ips
        try:
            infos = socket.getaddrinfo(
                host, port, type=socket.SOCK_STREAM, proto=socket.IPPROTO_TCP
            )
        except socket.gaierror as exc:
            raise AiError.from_category(
                ErrorCategory.DNS,
                message=f"无法解析主机名：{redact(host)}",
                cause=exc,
            ) from exc
        except OSError as exc:  # pragma: no cover - 极端环境
            raise AiError.from_category(
                ErrorCategory.DNS,
                message=f"解析主机名失败：{redact(host)}",
                cause=exc,
            ) from exc
        ips: list[str] = []
        for info in infos:
            sockaddr = info[4]
            if sockaddr and isinstance(sockaddr[0], str) and sockaddr[0] not in ips:
                ips.append(sockaddr[0])
        if not ips:
            raise AiError.from_category(
                ErrorCategory.DNS, message=f"主机名无解析结果：{redact(host)}"
            )
        with self._lock:
            self._cache[cache_key] = _CacheEntry(tuple(ips), now + self._ttl)
        return tuple(ips)

    def clear(self) -> None:
        with self._lock:
            self._cache.clear()


default_resolver = IpResolver()


def _normalize_ip(
    ip: ipaddress.IPv4Address | ipaddress.IPv6Address,
) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
    """IPv4-mapped IPv6 归一化，避免 ::ffff:127.0.0.1 绕过判定。"""
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        return ip.ipv4_mapped
    return ip


def _ip_is_metadata(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    normalized = _normalize_ip(ip)
    if str(normalized) in CLOUD_METADATA_IPS or str(ip) in CLOUD_METADATA_IPS:
        return True
    if isinstance(normalized, ipaddress.IPv4Address):
        # AWS/GCP/Azure/阿里云等常见元数据链路本地地址统一按元数据拒绝
        return normalized == ipaddress.IPv4Address("169.254.169.254") or normalized == ipaddress.IPv4Address(
            "100.100.100.200"
        )
    return normalized == ipaddress.IPv6Address("fd00:ec2::254")


def classify_blocked_ip(ip_text: str) -> str | None:
    """返回拒绝原因；允许时返回 None。"""
    try:
        raw_ip = ipaddress.ip_address(ip_text)
    except ValueError:
        return f"无法识别的 IP：{ip_text}"
    if _ip_is_metadata(raw_ip):
        return "云元数据地址"
    ip = _normalize_ip(raw_ip)
    if ip.is_loopback:
        return "loopback 地址"
    if ip.is_link_local:
        return "链路本地地址"
    if isinstance(ip, ipaddress.IPv6Address) and ip in _ULA_V6:
        return "唯一本地地址 fc00::/7"
    if ip.is_private:
        return "私有网段地址"
    return None


def parse_base_url(base_url: str) -> tuple[str, str, int]:
    """解析 baseUrl，返回 (scheme, host, port)；不合规直接抛 `blocked`。"""
    raw = (base_url or "").strip()
    if not raw:
        raise UrlBlockedError("baseUrl 不能为空")
    if "\\" in raw or any(ch.isspace() for ch in raw):
        raise UrlBlockedError("baseUrl 含非法字符")
    try:
        parts = urlsplit(raw)
    except ValueError as exc:
        raise UrlBlockedError(f"baseUrl 无法解析：{redact(raw)}") from exc
    scheme = (parts.scheme or "").lower()
    if scheme not in ALLOWED_SCHEMES:
        raise UrlBlockedError(f"只允许 http/https 协议，收到：{scheme or '(空)'}")
    if parts.username or parts.password:
        raise UrlBlockedError("baseUrl 不得包含用户名或密码等凭据")
    host = (parts.hostname or "").strip()
    if not host:
        raise UrlBlockedError("baseUrl 缺少主机名")
    try:
        port = parts.port
    except ValueError as exc:
        raise UrlBlockedError("baseUrl 端口非法（必须在 1-65535 之间）") from exc
    if port is None:
        port = 443 if scheme == "https" else 80
    if port < 1 or port > 65535:
        raise UrlBlockedError(f"baseUrl 端口非法：{port}")
    return scheme, host, port


def validate_url(
    base_url: str,
    *,
    allow_local_network: bool,
    resolver: IpResolver | None = None,
) -> UrlVerdict:
    """完整校验 baseUrl：协议/凭据/端口 + 全部解析 IP。"""
    scheme, host, port = parse_base_url(base_url)
    active_resolver = resolver or default_resolver
    try:
        literal: ipaddress.IPv4Address | ipaddress.IPv6Address | None = ipaddress.ip_address(
            host
        )
    except ValueError:
        literal = None
    ips: tuple[str, ...]
    if literal is not None:
        ips = (str(literal),)
    else:
        ips = active_resolver.resolve(host, port)

    for ip_text in ips:
        reason = classify_blocked_ip(ip_text)
        if reason is None:
            continue
        if reason == "云元数据地址":
            # 云元数据地址任何情况下都拒绝，即使开启本地网络
            raise UrlBlockedError(f"目标地址被安全策略拒绝：{reason}")
        if not allow_local_network:
            raise UrlBlockedError(
                f"目标地址被安全策略拒绝：{reason}"
                "（如需访问本地/内网模型请显式开启 allowLocalNetwork）"
            )
    logger.debug(
        "url_guard 通过 host=%s port=%s ipCount=%s allowLocal=%s",
        host,
        port,
        len(ips),
        allow_local_network,
    )
    return UrlVerdict(
        url=base_url, scheme=scheme, host=host, port=port, resolved_ips=ips
    )


def _default_port(scheme: str, port: int | None) -> int | None:
    if port is not None:
        return port
    if scheme == "http":
        return 80
    if scheme == "https":
        return 443
    return None


def _origin(url: httpx.URL) -> tuple[str, str | None, int | None]:
    return (url.scheme, url.host, _default_port(url.scheme, url.port))


def absolute_redirect_target(current_url: str, location: str) -> str:
    """把重定向 Location 解析为绝对地址（不做安全判定）。"""
    if not location:
        raise UrlBlockedError("重定向缺少 Location 头")
    return str(httpx.URL(current_url).join(location))


async def guarded_request(
    client: httpx.AsyncClient,
    method: str,
    url: str,
    *,
    allow_local_network: bool,
    resolver: IpResolver | None = None,
    max_redirects: int = MAX_REDIRECTS,
    **kwargs: object,
) -> httpx.Response:
    """发送请求并逐跳重新校验重定向目标（同主机、最多 max_redirects 跳）。"""
    if client.follow_redirects:  # pragma: no cover - 防御性检查
        raise AiError.internal("HTTP 客户端不得开启自动重定向")
    current_method = method.upper()
    current_url = url
    for _hop in range(max_redirects + 1):
        validate_url(
            current_url, allow_local_network=allow_local_network, resolver=resolver
        )
        response = await client.request(current_method, current_url, **kwargs)  # type: ignore[arg-type]
        if response.status_code not in _REDIRECT_STATUSES:
            return response
        location = response.headers.get("location", "")
        target = absolute_redirect_target(current_url, location)
        # 每一跳都重新校验（重新解析并校验全部 IP）——先做地址安全判定，
        # 这样“重定向到云元数据”会得到明确的 blocked 原因，而不是笼统的跨主机拒绝
        validate_url(target, allow_local_network=allow_local_network, resolver=resolver)
        if _origin(httpx.URL(target)) != _origin(httpx.URL(current_url)):
            raise UrlBlockedError("安全策略拒绝跨主机重定向")
        if response.status_code == 303 or (
            response.status_code in (301, 302) and current_method == "POST"
        ):
            current_method = "GET"
        current_url = target
    raise AiError.from_category(
        ErrorCategory.PROTOCOL, message="重定向次数超过上限"
    )


def clear_dns_cache() -> None:
    """清空默认解析器缓存（测试用）。"""
    default_resolver.clear()
