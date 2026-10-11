"""/api/v1/ai/explain 测试（mock httpx，不发真实网络请求）。"""

from __future__ import annotations

import json
import logging
from typing import Any

import httpx
import pytest

from app.errors import AiError, ErrorCategory
from app.providers.openai_compatible import OpenAiCompatibleProvider
from app.schemas import ExplainRequest
from app.services.explain_service import run_explain
from tests.conftest import LOCAL_BASE_URL, auth_headers, chat_completion


def build_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "question": {
            "stem": "TCP 建立连接需要几次握手？",
            "questionType": "single_choice",
            "options": [
                {"key": "A", "content": "1 次"},
                {"key": "B", "content": "2 次"},
                {"key": "C", "content": "3 次"},
            ],
            "answer": {"type": "single_choice", "value": "C"},
            "explanation": "三次握手确保双方收发能力与初始序号同步。",
        },
        "userAnswer": {"type": "single_choice", "value": "B"},
        "profile": {
            "id": "ollama-local",
            "provider": "openai-compatible",
            "baseUrl": LOCAL_BASE_URL,
            "model": "qwen3:8b",
            "timeoutSeconds": 30,
            "connectTimeoutSeconds": 2,
            "allowLocalNetwork": True,
            "parameters": {"temperature": 0.2, "maxTokens": 2048},
            "apiKey": "local-ollama-no-key-required",
        },
        "locale": "zh-CN",
    }
    payload.update(overrides)
    return payload


# ------------------------------------------------------------------ 成功路径


async def test_explain_success(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    content = json.dumps(
        {
            "explanation": "正确答案是 C：三次握手用于同步双方初始序号并确认收发能力。你选了 B，混淆了握手次数与报文段数量。",
            "tips": ["SYN → SYN/ACK → ACK", "握手次数 = 3，报文段也是 3"],
        },
        ensure_ascii=False,
    )
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["body"] = json.loads(request.content.decode())
        captured["headers"] = dict(request.headers)
        return httpx.Response(200, json=chat_completion(content))

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/explain", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["success"] is True
    assert body["error"] is None
    data = body["data"]
    assert "三次握手" in data["explanation"]
    assert data["tips"] == ["SYN → SYN/ACK → ACK", "握手次数 = 3，报文段也是 3"]
    assert data["model"] == "qwen3:8b"
    assert data["usage"]["total_tokens"] == 30
    assert isinstance(data["latencyMs"], int)
    # profileRef 只说明「用的是哪个档案」，形如 profile://<profileId>
    assert data["profileRef"] == "profile://ollama-local"
    assert data["profileRef"].startswith("profile://")

    # 请求侧断言：思考型模型必须有足够大的 max_tokens；密钥只在请求头
    assert captured["url"] == f"{LOCAL_BASE_URL}/chat/completions"
    assert captured["body"]["max_tokens"] == 2048
    assert captured["body"]["stream"] is False
    assert captured["headers"]["authorization"] == "Bearer local-ollama-no-key-required"
    system_prompt = captured["body"]["messages"][0]["content"]
    assert captured["body"]["messages"][0]["role"] == "system"
    assert "忽略" in system_prompt or "当作普通文本" in system_prompt
    user_prompt = captured["body"]["messages"][1]["content"]
    assert "UNTRUSTED_MATERIAL" in user_prompt
    assert "TCP 建立连接需要几次握手？" in user_prompt


async def test_explain_never_leaks_api_key_in_response_or_logs(
    async_client: httpx.AsyncClient,
    install_mock_provider: Any,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """响应与日志都不得出现 apiKey 明文（v1.1 契约 §7：只允许 profileId 与 provider）。"""
    fake_key = "sk-should-never-appear"
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["auth"] = request.headers.get("authorization")
        return httpx.Response(
            200,
            json=chat_completion("正确答案是 C：三次握手用于同步双方初始序号并确认收发能力。"),
        )

    install_mock_provider(httpx.MockTransport(handler))
    payload = build_payload()
    payload["profile"]["apiKey"] = fake_key
    with caplog.at_level(logging.DEBUG):
        response = await async_client.post(
            "/api/v1/ai/explain", json=payload, headers=auth_headers()
        )
    assert response.status_code == 200, response.text
    # 密钥只出现在发给供应商的请求头里
    assert captured["auth"] == f"Bearer {fake_key}"
    assert fake_key not in response.text
    assert fake_key not in caplog.text
    # 日志里只允许出现 profileId 与 provider（不含密钥相关字段）
    assert "profileId=ollama-local" in caplog.text
    assert "provider=openai-compatible" in caplog.text
    assert response.json()["data"]["profileRef"] == "profile://ollama-local"


async def test_explain_default_max_tokens_is_1024(
    install_mock_provider: Any,
) -> None:
    """未指定 maxTokens 时默认 1024（避免思考型模型 content 为空）。"""
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content.decode())
        return httpx.Response(200, json=chat_completion("这是一段足够长的中文讲解内容。"))

    install_mock_provider(httpx.MockTransport(handler))
    payload = build_payload()
    payload["profile"].pop("parameters")
    await run_explain(ExplainRequest.model_validate(payload))
    assert captured["body"]["max_tokens"] == 1024


async def test_explain_reasoning_content_fallback(
    install_mock_provider: Any,
) -> None:
    """content 为空时回退 reasoning_content（思考型模型）。"""
    payload = {
        "model": "qwen3:8b",
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "",
                    "reasoning_content": "先分析题干：三次握手的目的是同步初始序号，因此答案为 C。",
                },
            }
        ],
        "usage": {"total_tokens": 12},
    }

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    install_mock_provider(httpx.MockTransport(handler))
    outcome = await run_explain(ExplainRequest.model_validate(build_payload()))
    assert outcome.reasoning_only is True
    assert "三次握手" in outcome.response.explanation


async def test_explain_plain_text_response_is_accepted(
    install_mock_provider: Any,
) -> None:
    """模型返回纯文本（非 JSON）时退化为整体讲解，不失败。"""
    text = "答案是 C。第一次握手客户端发送 SYN，第二次服务端回 SYN/ACK，第三次客户端回 ACK。"

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=chat_completion(text))

    install_mock_provider(httpx.MockTransport(handler))
    outcome = await run_explain(ExplainRequest.model_validate(build_payload()))
    assert outcome.response.explanation == text
    assert outcome.response.tips == []


# ------------------------------------------------------------------ 错误分类


async def test_explain_provider_timeout_classified(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectTimeout("timed out", request=request)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/explain", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 504
    body = response.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_CONNECT_TIMEOUT"
    assert body["error"]["category"] == "timeout"
    assert body["error"]["retryable"] is True


async def test_explain_provider_read_timeout_classified(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("read timeout", request=request)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/explain", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 504
    assert response.json()["error"]["category"] == "timeout"


async def test_explain_provider_auth_failure_classified(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"message": "invalid api key"}})

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/explain", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 502
    body = response.json()
    assert body["error"]["code"] == "LLM_AUTH_FAILED"
    assert body["error"]["category"] == "auth"
    assert body["error"]["retryable"] is False
    # 绝不回传密钥或供应商完整错误
    assert "local-ollama-no-key-required" not in response.text


async def test_explain_empty_content_is_protocol_error(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """content 与 reasoning_content 均为空 → protocol 分类错误。"""
    payload = {
        "model": "qwen3:8b",
        "choices": [{"index": 0, "message": {"role": "assistant", "content": ""}}],
    }

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=payload)

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/explain", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 503
    body = response.json()
    assert body["error"]["category"] == "protocol"
    assert body["error"]["code"] == "LLM_UNAVAILABLE"


async def test_explain_blocked_base_url(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """allowLocalNetwork=false 时 loopback 地址被拒（BASE_URL_BLOCKED）。"""
    install_mock_provider(httpx.MockTransport(lambda request: httpx.Response(200, json={})))
    payload = build_payload()
    payload["profile"]["allowLocalNetwork"] = False
    response = await async_client.post(
        "/api/v1/ai/explain", json=payload, headers=auth_headers()
    )
    assert response.status_code == 400
    body = response.json()
    assert body["error"]["code"] == "BASE_URL_BLOCKED"
    assert body["error"]["category"] == "blocked"


async def test_explain_provider_5xx_is_unavailable(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    install_mock_provider(
        httpx.MockTransport(
            lambda request: httpx.Response(503, json={"error": "model loading"})
        )
    )
    response = await async_client.post(
        "/api/v1/ai/explain", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 503
    assert response.json()["error"]["code"] == "LLM_UNAVAILABLE"


async def test_explain_oversized_body_rejected(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """响应体超过 1 MiB 上限 → protocol 分类。"""
    big = "x" * (1024 * 1024 + 64)
    install_mock_provider(
        httpx.MockTransport(
            lambda request: httpx.Response(200, json=chat_completion(big))
        )
    )
    response = await async_client.post(
        "/api/v1/ai/explain", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 503
    assert response.json()["error"]["category"] == "protocol"


async def test_openai_provider_requires_choices(
    install_mock_provider: Any,
) -> None:
    """响应缺少 choices → protocol 分类（协议不兼容）。"""
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json={"foo": "bar"}))
    )
    profile = ExplainRequest.model_validate(build_payload()).profile
    provider = OpenAiCompatibleProvider(profile)
    with pytest.raises(AiError) as excinfo:
        await provider.chat([])
    assert excinfo.value.category == ErrorCategory.PROTOCOL
