"""/api/v1/ai/test-connection 与 /profiles/{id}/probe 测试。"""

from __future__ import annotations

from typing import Any

import httpx
import pytest

from tests.conftest import LOCAL_BASE_URL, auth_headers

MODELS_PAYLOAD = {
    "object": "list",
    "data": [
        {"id": "qwen3:8b", "object": "model"},
        {"id": "llama3:8b", "object": "model"},
    ],
}
TAGS_PAYLOAD = {
    "models": [
        {"name": "qwen3:8b", "model": "qwen3:8b"},
        {"name": "nomic-embed-text:latest", "model": "nomic-embed-text:latest"},
    ]
}


def profile_payload(provider: str = "openai-compatible", **overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "id": "ollama-local",
        "provider": provider,
        "baseUrl": LOCAL_BASE_URL,
        "model": "qwen3:8b",
        "timeoutSeconds": 5,
        "connectTimeoutSeconds": 2,
        "allowLocalNetwork": True,
        "parameters": {},
        "apiKey": "local-ollama-no-key-required",
    }
    payload.update(overrides)
    return payload


# ------------------------------------------------------------------ test-connection


async def test_test_connection_ok(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["method"] = request.method
        captured["headers"] = dict(request.headers)
        return httpx.Response(200, json=MODELS_PAYLOAD)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload()},
        headers=auth_headers(),
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["success"] is True
    data = body["data"]
    assert data["ok"] is True
    assert data["category"] == "ok"
    assert data["httpStatus"] == 200
    assert data["modelEcho"] == "qwen3:8b"
    assert isinstance(data["latencyMs"], int)
    assert data["message"]
    assert data["suggestion"]
    # 最小化请求：GET /models
    assert captured["url"] == f"{LOCAL_BASE_URL}/models"
    assert captured["method"] == "GET"
    # 绝不回传请求头或密钥
    assert "authorization" not in {key.lower() for key in data}
    assert "local-ollama-no-key-required" not in response.text


async def test_test_connection_ollama_uses_native_tags(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        return httpx.Response(200, json=TAGS_PAYLOAD)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload(provider="ollama", baseUrl="http://127.0.0.1:11434")},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["ok"] is True
    assert captured["url"] == "http://127.0.0.1:11434/api/tags"
    assert data["modelEcho"] == "qwen3:8b"


async def test_test_connection_model_echo_falls_back_to_first(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json=TAGS_PAYLOAD))
    )
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={
            "profile": profile_payload(
                provider="ollama", baseUrl="http://127.0.0.1:11434", model="not-pulled:1b"
            )
        },
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["modelEcho"] == "qwen3:8b"


async def test_test_connection_auth_failure(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """供应商 401 → HTTP 200 + ok=false + category=auth（不抛 502）。"""
    install_mock_provider(
        httpx.MockTransport(
            lambda request: httpx.Response(401, json={"error": {"message": "bad key"}})
        )
    )
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload()},
        headers=auth_headers(),
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["ok"] is False
    assert data["category"] == "auth"
    assert data["httpStatus"] == 401
    assert "key" in data["suggestion"].lower() or "密钥" in data["suggestion"]


async def test_test_connection_timeout(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("timeout", request=request)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload()},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["ok"] is False
    assert data["category"] == "timeout"
    assert data["httpStatus"] is None


async def test_test_connection_dns_failure(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("getaddrinfo failed", request=request)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload(baseUrl="http://no-such-host.invalid/v1")},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["ok"] is False
    assert data["category"] == "dns"


async def test_test_connection_blocked_base_url(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    install_mock_provider(httpx.MockTransport(lambda request: httpx.Response(200, json={})))
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload(allowLocalNetwork=False)},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["ok"] is False
    assert data["category"] == "blocked"
    assert "本地网络" in data["suggestion"]


async def test_test_connection_quota(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(429, json={"error": "rate limit"}))
    )
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload()},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["category"] == "quota"
    assert data["httpStatus"] == 429


async def test_test_connection_uses_request_api_key(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """body 级 apiKey 覆盖 profile.apiKey，并且不会出现在响应里。"""
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["auth"] = request.headers.get("authorization")
        return httpx.Response(200, json=MODELS_PAYLOAD)

    install_mock_provider(httpx.MockTransport(handler))
    payload_profile = profile_payload()
    payload_profile.pop("apiKey")
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": payload_profile, "apiKey": "sk-transient-9999"},
        headers=auth_headers(),
    )
    assert response.json()["data"]["ok"] is True
    assert captured["auth"] == "Bearer sk-transient-9999"
    assert "sk-transient-9999" not in response.text


async def test_test_connection_rejects_empty_api_key(
    async_client: httpx.AsyncClient, app: Any
) -> None:
    payload_profile = profile_payload()
    payload_profile.pop("apiKey")
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": payload_profile, "apiKey": ""},
        headers=auth_headers(),
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


async def test_test_connection_unsupported_provider(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    install_mock_provider(httpx.MockTransport(lambda request: httpx.Response(200, json={})))
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload(provider="anthropic")},
        headers=auth_headers(),
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


# ------------------------------------------------------------------ probe


async def test_probe_without_api_key(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["auth"] = request.headers.get("authorization")
        return httpx.Response(200, json=MODELS_PAYLOAD)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.get(
        "/api/v1/ai/profiles/ollama-local/probe",
        params={"baseUrl": LOCAL_BASE_URL, "allowLocalNetwork": "true"},
        headers=auth_headers(),
    )
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["profileId"] == "ollama-local"
    assert data["reachable"] is True
    assert data["models"] == ["qwen3:8b", "llama3:8b"]
    assert data["capabilities"] == {"chatCompletions": True, "modelsEndpoint": True}
    assert captured["auth"] is None, "探测不得携带密钥"


async def test_probe_reachable_when_models_requires_auth(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """端点响应 401 说明服务可达（只是需要密钥）。"""
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(401, json={"error": "auth"}))
    )
    response = await async_client.get(
        "/api/v1/ai/profiles/p1/probe",
        params={"baseUrl": LOCAL_BASE_URL, "allowLocalNetwork": "true"},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["reachable"] is True
    assert data["models"] == []


async def test_probe_unreachable_on_connection_error(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.get(
        "/api/v1/ai/profiles/p1/probe",
        params={"baseUrl": LOCAL_BASE_URL, "allowLocalNetwork": "true"},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["reachable"] is False
    assert data["capabilities"]["chatCompletions"] is False


async def test_probe_requires_base_url(
    async_client: httpx.AsyncClient,
) -> None:
    response = await async_client.get(
        "/api/v1/ai/profiles/p1/probe", headers=auth_headers()
    )
    assert response.status_code == 400
    assert response.json()["error"]["code"] == "VALIDATION_ERROR"


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        (400, "protocol"),
        (404, "protocol"),
        (500, "unavailable"),
    ],
)
async def test_test_connection_status_categories(
    async_client: httpx.AsyncClient, install_mock_provider: Any, status: int, expected: str
) -> None:
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(status, json={"error": "x"}))
    )
    response = await async_client.post(
        "/api/v1/ai/test-connection",
        json={"profile": profile_payload()},
        headers=auth_headers(),
    )
    data = response.json()["data"]
    assert data["category"] == expected
    assert data["httpStatus"] == status
