"""基础接口与内部令牌鉴权测试（契约 §0、§7）。"""

from __future__ import annotations

import re

import httpx
from starlette.testclient import TestClient

from tests.conftest import TEST_TOKEN, auth_headers

REQUEST_ID_PATTERN = re.compile(r"^req_[0-9a-f]{16}$")


def test_health_without_token(sync_client: TestClient) -> None:
    """GET /health 不鉴权，字段与契约 §7 完全一致。"""
    response = sync_client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body == {
        "status": "UP",
        "service": "python-ai",
        "version": "1.0.0",
        "providers": ["openai-compatible", "ollama"],
    }


def test_explain_without_token_returns_401(sync_client: TestClient) -> None:
    """缺失内部令牌 → 401 + UNAUTHORIZED + 契约失败包络。"""
    response = sync_client.post("/api/v1/ai/explain", json={})
    assert response.status_code == 401
    body = response.json()
    assert body["success"] is False
    assert body["data"] is None
    assert body["error"]["code"] == "UNAUTHORIZED"
    assert body["error"]["retryable"] is False
    assert REQUEST_ID_PATTERN.match(body["requestId"])
    assert body["timestamp"].endswith("Z")


def test_wrong_token_returns_401(sync_client: TestClient) -> None:
    response = sync_client.post(
        "/api/v1/ai/grade-subjective",
        json={},
        headers={"X-Internal-Token": "wrong-token"},
    )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_token_accepted_then_validation_error(sync_client: TestClient) -> None:
    """令牌正确但载荷非法 → 400 VALIDATION_ERROR（说明鉴权已通过）。"""
    response = sync_client.post(
        "/api/v1/ai/explain", json={"profile": {}}, headers=auth_headers()
    )
    assert response.status_code == 400
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"
    assert body["error"]["details"], "字段级错误明细不能为空"


def test_probe_requires_token(sync_client: TestClient) -> None:
    response = sync_client.get(
        "/api/v1/ai/profiles/p1/probe",
        params={"baseUrl": "http://127.0.0.1:11434/v1"},
    )
    assert response.status_code == 401


def test_test_connection_requires_token(sync_client: TestClient) -> None:
    response = sync_client.post("/api/v1/ai/test-connection", json={})
    assert response.status_code == 401


def test_origin_header_rejected(sync_client: TestClient) -> None:
    """浏览器来源一律拒绝（本服务只供 Java 进程调用）。"""
    response = sync_client.post(
        "/api/v1/ai/explain",
        json={},
        headers={**auth_headers(), "Origin": "http://evil.example"},
    )
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "ORIGIN_REJECTED"


async def test_health_ignores_origin(async_client: httpx.AsyncClient) -> None:
    response = await async_client.get("/health", headers={"Origin": "http://evil.example"})
    assert response.status_code == 200
    assert response.headers["x-request-id"].startswith("req_")


def test_unknown_path_uses_envelope(sync_client: TestClient) -> None:
    response = sync_client.get("/api/v1/ai/nope", headers=auth_headers())
    assert response.status_code == 404
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "RESOURCE_NOT_FOUND"


def test_token_is_never_echoed(sync_client: TestClient) -> None:
    """任何响应都不得回显内部令牌。"""
    for response in (
        sync_client.get("/health"),
        sync_client.post("/api/v1/ai/explain", json={}),
    ):
        assert TEST_TOKEN not in response.text
