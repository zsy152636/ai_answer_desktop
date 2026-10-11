"""pytest 公共夹具。

测试原则（交付要求第 2 条）：
- 全部外部供应商调用用 httpx.MockTransport 模拟，不发真实网络请求；
- URL 校验使用 IP 字面量，避免依赖真实 DNS。
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Callable
from typing import Any

import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI
from starlette.testclient import TestClient

from app.config import Settings
from app.main import create_app

TEST_TOKEN = "test-internal-token-0123456789abcdef"
LOCAL_BASE_URL = "http://127.0.0.1:11434/v1"


@pytest.fixture
def settings() -> Settings:
    return Settings(host="127.0.0.1", port=0, internal_token=TEST_TOKEN)


@pytest.fixture
def app(settings: Settings) -> FastAPI:
    return create_app(settings)


@pytest.fixture
def sync_client(app: FastAPI) -> TestClient:
    """同步 TestClient（健康检查、鉴权包络等无需 mock 的用例）。"""
    return TestClient(app, raise_server_exceptions=False)


@pytest_asyncio.fixture
async def async_client(app: FastAPI) -> AsyncIterator[httpx.AsyncClient]:
    """ASGI 直连客户端；base_url 用 loopback 以满足 url_guard。"""
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    client = httpx.AsyncClient(
        transport=transport, base_url="http://127.0.0.1:9000", timeout=10.0
    )
    try:
        yield client
    finally:
        await client.aclose()


def auth_headers(token: str = TEST_TOKEN) -> dict[str, str]:
    return {"X-Internal-Token": token}


@pytest.fixture
def install_mock_provider(
    monkeypatch: pytest.MonkeyPatch,
) -> Callable[[httpx.MockTransport], None]:
    """把 provider 的默认 HTTP 客户端替换为 MockTransport 客户端。"""

    def _install(transport: httpx.MockTransport) -> None:
        import app.providers.base as base_module

        def factory(profile: Any) -> httpx.AsyncClient:
            return httpx.AsyncClient(
                transport=transport,
                timeout=profile.httpx_timeout,
                follow_redirects=False,
            )

        monkeypatch.setattr(base_module, "default_client_factory", factory)

    return _install


def json_response(
    payload: dict[str, Any], status_code: int = 200
) -> httpx.Response:
    return httpx.Response(status_code, json=payload)


def chat_completion(
    content: str, *, model: str = "qwen3:8b", usage: dict[str, Any] | None = None
) -> dict[str, Any]:
    return {
        "id": "chatcmpl-test",
        "object": "chat.completion",
        "model": model,
        "choices": [{"index": 0, "message": {"role": "assistant", "content": content}}],
        "usage": usage or {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
    }
