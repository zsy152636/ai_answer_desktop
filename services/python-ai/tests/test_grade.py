"""/api/v1/ai/grade-subjective 测试：JSON 容错解析、分数夹取、错误分类。"""

from __future__ import annotations

import json
import logging
from typing import Any

import httpx
import pytest

from app.schemas import GradeSubjectiveRequest
from app.services.grading import compute_criterion_maxima, run_subjective_grading
from app.services.json_parse import extract_json_object
from tests.conftest import LOCAL_BASE_URL, auth_headers, chat_completion

RUBRIC = {
    "criteria": [
        {"id": "c1", "desc": "指出三次握手顺序", "weight": 60},
        {"id": "c2", "desc": "说明握手目的", "weight": 40},
    ],
    "maxScore": 10,
}


def build_payload(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "question": {
            "stem": "请说明 TCP 三次握手的过程与目的。",
            "referenceAnswer": "客户端 SYN，服务端 SYN/ACK，客户端 ACK；目的是同步初始序号并确认双方收发能力。",
            "rubric": RUBRIC,
        },
        "userAnswer": "客户端先发 SYN，服务端回 SYN/ACK，客户端再发 ACK，用来确认双方都能收发。",
        "profile": {
            "id": "ollama-local",
            "provider": "openai-compatible",
            "baseUrl": LOCAL_BASE_URL,
            "model": "qwen3:8b",
            "allowLocalNetwork": True,
            "parameters": {"maxTokens": 1024},
            "apiKey": "local-ollama-no-key-required",
        },
        "maxScore": 10,
    }
    payload.update(overrides)
    return payload


# ------------------------------------------------------------------ JSON 容错解析


def test_extract_json_with_fence() -> None:
    text = '```json\n{"criteria": [], "confidence": 0.8}\n```'
    assert extract_json_object(text)["confidence"] == 0.8


def test_extract_json_with_surrounding_prose() -> None:
    text = (
        "好的，这是我的评分结果：\n"
        '{"criteria": [{"id": "c1", "score": 5, "reason": "顺序正确"}], "confidence": 0.7}\n'
        "以上仅供参考。"
    )
    payload = extract_json_object(text)
    assert payload["criteria"][0]["id"] == "c1"


def test_extract_json_containing_braces_in_strings() -> None:
    text = '前缀 {"reason": "答案里出现了 { } 符号", "score": 1} 后缀'
    payload = extract_json_object(text)
    assert payload["reason"] == "答案里出现了 { } 符号"


def test_extract_json_nested_objects() -> None:
    text = '说明：{"outer": {"inner": 1}, "criteria": [{"id": "c1", "score": 2}]}'
    payload = extract_json_object(text)
    assert payload["outer"] == {"inner": 1}


def test_extract_json_escaped_quotes() -> None:
    text = r'{"reason": "他说 \"三次握手\" 正确", "score": 3}'
    assert extract_json_object(text)["score"] == 3


@pytest.mark.parametrize(
    "text",
    ["", "   ", "完全没有 JSON 的回答", "{不是合法 JSON}", "[1, 2, 3]"],
)
def test_extract_json_failure_is_protocol_error(text: str) -> None:
    from app.errors import AiError, ErrorCategory

    with pytest.raises(AiError) as excinfo:
        extract_json_object(text)
    assert excinfo.value.category is ErrorCategory.PROTOCOL


# ------------------------------------------------------------------ 分值换算


def test_criterion_maxima_percentage_weights() -> None:
    """权重合计 100 → 按百分制换算。"""
    request = GradeSubjectiveRequest.model_validate(build_payload())
    maxima = compute_criterion_maxima(request.question.rubric, 10)
    assert maxima == {"c1": 6.0, "c2": 4.0}


def test_criterion_maxima_normalised_weights() -> None:
    from app.schemas import Rubric

    rubric = Rubric.model_validate(
        {"criteria": [{"id": "a", "weight": 3}, {"id": "b", "weight": 1}]}
    )
    assert compute_criterion_maxima(rubric, 20) == {"a": 15.0, "b": 5.0}


def test_criterion_maxima_without_weights_splits_evenly() -> None:
    from app.schemas import Rubric

    rubric = Rubric.model_validate({"criteria": [{"id": "a"}, {"id": "b"}]})
    assert compute_criterion_maxima(rubric, 10) == {"a": 5.0, "b": 5.0}


def test_criterion_maxima_accepts_description_alias() -> None:
    from app.schemas import Rubric

    rubric = Rubric.model_validate(
        {"criteria": [{"id": "c1", "description": "要点一", "weight": 100}]}
    )
    assert rubric.criteria[0].description == "要点一"


# ------------------------------------------------------------------ 评分主流程


async def test_grade_success_with_fenced_json(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    content = (
        "这是我的评分：\n```json\n"
        + json.dumps(
            {
                "criteria": [
                    {"id": "c1", "score": 6, "reason": "顺序完全正确"},
                    {"id": "c2", "score": 3, "reason": "目的提到但不完整"},
                ],
                "confidence": 0.82,
                "suggestedScore": 9,
            },
            ensure_ascii=False,
        )
        + "\n```\n请复核。"
    )
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["body"] = json.loads(request.content.decode())
        return httpx.Response(200, json=chat_completion(content, model="qwen3:8b"))

    install_mock_provider(httpx.MockTransport(handler))
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 200, response.text
    data = response.json()["data"]
    assert data["suggestedScore"] == 9
    assert data["maxScore"] == 10
    assert data["needsReview"] is True
    assert data["confidence"] == 0.82
    assert data["model"] == "qwen3:8b"
    assert [item["id"] for item in data["criteria"]] == ["c1", "c2"]
    assert data["criteria"][0]["score"] == 6
    assert data["criteria"][0]["reason"] == "顺序完全正确"
    # 提示词里必须给出严格 JSON schema，并要求 json 输出
    assert captured["body"]["response_format"] == {"type": "json_object"}
    prompt = captured["body"]["messages"][1]["content"]
    assert '"criteria"' in prompt and "confidence" in prompt
    assert "UNTRUSTED_MATERIAL" in prompt


async def test_grade_clamps_suggested_score(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """模型给出 15 分（超过满分 10）→ 夹取到 10。"""
    content = json.dumps(
        {
            "criteria": [{"id": "c1", "score": 99, "reason": "x"}, {"id": "c2", "score": 99}],
            "confidence": 1.5,
            "suggestedScore": 15,
        },
        ensure_ascii=False,
    )
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json=chat_completion(content)))
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    data = response.json()["data"]
    assert data["suggestedScore"] == 10
    # 逐项分数不得超过该细则分值（c1 上限 6，c2 上限 4）
    assert data["criteria"][0]["score"] <= 6
    assert data["criteria"][1]["score"] <= 4
    assert sum(item["score"] for item in data["criteria"]) <= 10
    # 置信度夹取到 [0,1]
    assert data["confidence"] == 1.0


async def test_grade_clamps_negative_score(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    content = json.dumps(
        {
            "criteria": [
                {"id": "c1", "score": -5, "reason": "负分"},
                {"id": "c2", "score": 0, "reason": "未提及"},
            ],
            "confidence": -0.2,
        },
        ensure_ascii=False,
    )
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json=chat_completion(content)))
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    data = response.json()["data"]
    assert all(item["score"] >= 0 for item in data["criteria"])
    assert data["suggestedScore"] == 0
    assert data["confidence"] == 0.0


async def test_grade_computes_suggested_score_when_missing(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    content = json.dumps(
        {
            "criteria": [
                {"id": "c1", "score": 6, "reason": "ok"},
                {"id": "c2", "score": 2, "reason": "部分"},
            ],
            "confidence": "0.6",
        },
        ensure_ascii=False,
    )
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json=chat_completion(content)))
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    data = response.json()["data"]
    assert data["suggestedScore"] == 8
    assert data["confidence"] == 0.6


async def test_grade_fills_missing_criteria_from_rubric(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """模型漏掉细则时补 0 分项，保证 criteria 与 rubric 对齐。"""
    content = json.dumps(
        {"criteria": [{"id": "c1", "score": 6, "reason": "ok"}], "confidence": 0.5},
        ensure_ascii=False,
    )
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json=chat_completion(content)))
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    data = response.json()["data"]
    assert {item["id"] for item in data["criteria"]} == {"c1", "c2"}
    assert data["needsReview"] is True


async def test_grade_response_has_profile_ref(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """响应带 profileRef（profile://<profileId>），只标识本次使用的档案。"""
    content = json.dumps(
        {"criteria": [{"id": "c1", "score": 6, "reason": "ok"}], "confidence": 0.5},
        ensure_ascii=False,
    )
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json=chat_completion(content)))
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    data = response.json()["data"]
    assert data["profileRef"] == "profile://ollama-local"
    assert data["profileRef"].startswith("profile://")


async def test_grade_never_leaks_api_key_in_response_or_logs(
    async_client: httpx.AsyncClient,
    install_mock_provider: Any,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """评分链路的响应与日志都不得出现 apiKey 明文（v1.1 契约 §7）。"""
    fake_key = "sk-should-never-appear"
    captured: dict[str, Any] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["auth"] = request.headers.get("authorization")
        content = json.dumps(
            {
                "criteria": [
                    {"id": "c1", "score": 6, "reason": "顺序正确"},
                    {"id": "c2", "score": 3, "reason": "目的基本说清"},
                ],
                "confidence": 0.6,
            },
            ensure_ascii=False,
        )
        return httpx.Response(200, json=chat_completion(content))

    install_mock_provider(httpx.MockTransport(handler))
    payload = build_payload()
    payload["profile"]["apiKey"] = fake_key
    with caplog.at_level(logging.DEBUG):
        response = await async_client.post(
            "/api/v1/ai/grade-subjective", json=payload, headers=auth_headers()
        )
    assert response.status_code == 200, response.text
    assert captured["auth"] == f"Bearer {fake_key}"
    assert fake_key not in response.text
    assert fake_key not in caplog.text
    assert "profileId=ollama-local" in caplog.text
    assert "provider=openai-compatible" in caplog.text


async def test_grade_protocol_error_on_unparsable_answer(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    """模型返回无法解析的内容 → protocol 分类、LLM_UNAVAILABLE、503。"""
    install_mock_provider(
        httpx.MockTransport(
            lambda request: httpx.Response(
                200, json=chat_completion("我觉得写得不错，给 8 分吧。")
            )
        )
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 503
    body = response.json()
    assert body["error"]["category"] == "protocol"
    assert body["error"]["code"] == "LLM_UNAVAILABLE"


async def test_grade_protocol_error_without_criteria(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    install_mock_provider(
        httpx.MockTransport(
            lambda request: httpx.Response(
                200, json=chat_completion('{"confidence": 0.9}')
            )
        )
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 503
    assert response.json()["error"]["category"] == "protocol"


async def test_grade_auth_failure_classified(
    async_client: httpx.AsyncClient, install_mock_provider: Any
) -> None:
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(401, json={"error": "no key"}))
    )
    response = await async_client.post(
        "/api/v1/ai/grade-subjective", json=build_payload(), headers=auth_headers()
    )
    assert response.status_code == 502
    assert response.json()["error"]["category"] == "auth"


async def test_grade_without_rubric_uses_even_split(
    install_mock_provider: Any,
) -> None:
    """无评分细则时 criteria 可为空，但分数仍需夹取。"""
    content = json.dumps(
        {"criteria": [{"id": "extra-1", "score": 3, "reason": "自主要点"}], "confidence": 0.4},
        ensure_ascii=False,
    )
    install_mock_provider(
        httpx.MockTransport(lambda request: httpx.Response(200, json=chat_completion(content)))
    )
    payload = build_payload()
    payload["question"].pop("rubric")
    outcome = await run_subjective_grading(GradeSubjectiveRequest.model_validate(payload))
    assert outcome.response.suggested_score == 0
    assert outcome.response.criteria[0].score == 0
    assert outcome.response.needs_review is True
