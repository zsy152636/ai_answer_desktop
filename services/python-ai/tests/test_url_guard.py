"""url_guard 安全校验测试（开发文档 §6.4、契约 §6）。"""

from __future__ import annotations

import httpx
import pytest

from app.errors import AiError, ErrorCategory, ErrorCode
from app.providers.ollama import OllamaProvider
from app.schemas import LlmCallProfile
from app.security.url_guard import (
    IpResolver,
    UrlBlockedError,
    classify_blocked_ip,
    guarded_request,
    parse_base_url,
    validate_url,
)


def profile(base_url: str, *, allow_local: bool, provider: str = "openai-compatible") -> LlmCallProfile:
    return LlmCallProfile.model_validate(
        {
            "id": "p1",
            "provider": provider,
            "baseUrl": base_url,
            "model": "qwen3:8b",
            "allowLocalNetwork": allow_local,
        }
    )


# ------------------------------------------------------------------ 拦截分支


@pytest.mark.parametrize(
    ("base_url", "reason_fragment"),
    [
        ("http://127.0.0.1:11434/v1", "loopback"),
        ("http://127.5.6.7:8080", "loopback"),
        ("http://[::1]:11434/v1", "loopback"),
        ("http://10.0.0.8:8000/v1", "私有网段"),
        ("http://172.16.5.4:8000/v1", "私有网段"),
        ("http://172.31.255.254:8000/v1", "私有网段"),
        ("http://192.168.1.20:8000/v1", "私有网段"),
        ("http://169.254.10.10:8000/v1", "链路本地"),
        ("http://[fe80::1]:8000/v1", "链路本地"),
        ("http://[fc00::1]:8000/v1", "唯一本地"),
        ("http://169.254.169.254/latest/meta-data", "云元数据"),
        ("http://100.100.100.200/v1", "云元数据"),
    ],
)
def test_blocked_addresses_when_local_disabled(base_url: str, reason_fragment: str) -> None:
    with pytest.raises(UrlBlockedError) as excinfo:
        validate_url(base_url, allow_local_network=False, resolver=IpResolver())
    error = excinfo.value
    assert error.code is ErrorCode.BASE_URL_BLOCKED
    assert error.category is ErrorCategory.BLOCKED
    assert error.http_status == 400
    assert reason_fragment in error.message


def test_metadata_blocked_even_with_local_allowed() -> None:
    """云元数据地址任何情况下都拒绝。"""
    for base_url in ("http://169.254.169.254/v1", "http://100.100.100.200/v1"):
        with pytest.raises(UrlBlockedError) as excinfo:
            validate_url(base_url, allow_local_network=True, resolver=IpResolver())
        assert "云元数据" in excinfo.value.message


@pytest.mark.parametrize(
    "base_url",
    [
        "ftp://example.com/api",
        "file:///etc/passwd",
        "gopher://127.0.0.1:11434",
        "ws://example.com/v1",
        "example.com/v1",
    ],
)
def test_only_http_https_allowed(base_url: str) -> None:
    with pytest.raises(UrlBlockedError) as excinfo:
        parse_base_url(base_url)
    assert "http/https" in excinfo.value.message


@pytest.mark.parametrize(
    "base_url",
    [
        "http://user:pass@example.com/v1",
        "https://sk-secret@example.com/v1",
    ],
)
def test_url_with_userinfo_rejected(base_url: str) -> None:
    """URL 里带 userinfo（user:pass@）一律拒绝，避免把密钥写进 baseUrl。"""
    with pytest.raises(UrlBlockedError) as excinfo:
        parse_base_url(base_url)
    assert "凭据" in excinfo.value.message


@pytest.mark.parametrize("base_url", ["http://example.com:0/v1", "http://example.com:70000/v1"])
def test_invalid_port_rejected(base_url: str) -> None:
    with pytest.raises(UrlBlockedError):
        parse_base_url(base_url)


def test_whitespace_rejected() -> None:
    with pytest.raises(UrlBlockedError):
        parse_base_url("http://example.com/a b")


# ------------------------------------------------------------------ 允许分支


def test_loopback_allowed_when_local_enabled() -> None:
    verdict = validate_url(
        "http://127.0.0.1:11434/v1", allow_local_network=True, resolver=IpResolver()
    )
    assert verdict.host == "127.0.0.1"
    assert verdict.port == 11434
    assert verdict.resolved_ips == ("127.0.0.1",)


def test_private_network_allowed_when_local_enabled() -> None:
    verdict = validate_url(
        "http://192.168.1.50:8080/v1", allow_local_network=True, resolver=IpResolver()
    )
    assert verdict.port == 8080


def test_public_literal_allowed() -> None:
    verdict = validate_url(
        "https://8.8.8.8/v1", allow_local_network=False, resolver=IpResolver()
    )
    assert verdict.scheme == "https"
    assert verdict.port == 443


def test_default_ports() -> None:
    assert parse_base_url("http://example.com/api")[2] == 80
    assert parse_base_url("https://example.com/api")[2] == 443


def test_ipv4_mapped_ipv6_is_classified_as_loopback() -> None:
    """::ffff:127.0.0.1 不能绕过 loopback 判定。"""
    assert classify_blocked_ip("::ffff:127.0.0.1") == "loopback 地址"
    with pytest.raises(UrlBlockedError):
        validate_url(
            "http://[::ffff:127.0.0.1]:11434/v1",
            allow_local_network=False,
            resolver=IpResolver(),
        )


def test_dns_failure_is_classified_as_dns() -> None:
    resolver = IpResolver()
    with pytest.raises(AiError) as excinfo:
        resolver.resolve("no-such-host.invalid", 80)
    assert excinfo.value.category is ErrorCategory.DNS


def test_resolver_cache_is_used() -> None:
    resolver = IpResolver()
    first = resolver.resolve("127.0.0.1", 11434)
    cached = resolver.resolve("127.0.0.1", 11434)
    assert first == cached == ("127.0.0.1",)


# ------------------------------------------------------------------ 重定向逐跳校验


async def test_redirect_to_metadata_is_blocked() -> None:
    """每一跳重定向都要重新校验：302 → 云元数据必须被拦。"""
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        return httpx.Response(302, headers={"location": "http://169.254.169.254/v1/models"})

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport, follow_redirects=False) as client:
        with pytest.raises(UrlBlockedError) as excinfo:
            await guarded_request(
                client,
                "GET",
                "http://127.0.0.1:11434/v1/models",
                allow_local_network=True,
                resolver=IpResolver(),
            )
    assert "云元数据" in excinfo.value.message
    assert len(calls) == 1, "被拒绝的目标不应真正发起请求"


async def test_cross_host_redirect_rejected() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(302, headers={"location": "http://8.8.8.8/v1/models"})

    transport = httpx.MockTransport(handler)
    async with httpx.AsyncClient(transport=transport, follow_redirects=False) as client:
        with pytest.raises(UrlBlockedError) as excinfo:
            await guarded_request(
                client,
                "GET",
                "http://127.0.0.1:11434/v1/models",
                allow_local_network=True,
                resolver=IpResolver(),
            )
    assert "跨主机" in excinfo.value.message


async def test_provider_sets_no_redirect_following() -> None:
    """provider 的默认客户端必须关闭自动重定向。"""
    from app.providers.base import default_client_factory

    client = default_client_factory(profile("http://127.0.0.1:11434/v1", allow_local=True))
    try:
        assert client.follow_redirects is False
    finally:
        await client.aclose()


async def test_guard_rejects_client_with_auto_redirect() -> None:
    transport = httpx.MockTransport(lambda request: httpx.Response(200, json={}))
    async with httpx.AsyncClient(transport=transport, follow_redirects=True) as client:
        with pytest.raises(AiError):
            await guarded_request(
                client,
                "GET",
                "http://127.0.0.1:11434/v1/models",
                allow_local_network=True,
                resolver=IpResolver(),
            )


async def test_ollama_provider_blocks_disallowed_base_url() -> None:
    """provider 内部调用同样经过 url_guard（不能只在路由层校验）。"""
    provider = OllamaProvider(profile("http://10.1.2.3:11434", allow_local=False, provider="ollama"))
    with pytest.raises(UrlBlockedError):
        await provider.health_probe()
