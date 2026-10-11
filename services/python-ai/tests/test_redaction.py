"""redaction 脱敏测试（契约 §6：日志与异常消息必须脱敏）。"""

from __future__ import annotations

from app.security.redaction import MASK, redact, redact_mapping, safe_repr


def test_redact_bearer_header() -> None:
    text = "Authorization: Bearer sk-live-abcdefghijklmnop"
    result = redact(text)
    assert "sk-live-abcdefghijklmnop" not in result
    # Authorization 整体走 Key-Value 规则，保留末 4 位便于比对
    assert result == "Authorization: ****mnop"


def test_redact_bearer_outside_header() -> None:
    result = redact("upstream rejected Bearer sk-live-abcdefghijklmnop")
    assert "sk-live-abcdefghijklmnop" not in result
    assert result == "upstream rejected Bearer ****mnop"


def test_redact_authorization_json_form() -> None:
    result = redact('{"authorization": "Bearer sk-abcdefgh1234"}')
    assert "sk-abcdefgh1234" not in result
    assert "****1234" in result


def test_redact_api_key_assignment() -> None:
    result = redact('{"api_key": "sk-1234567890abcdef"}')
    assert "sk-1234567890abcdef" not in result
    assert MASK in result
    assert "cdef" in result


def test_redact_query_string_and_key_value_forms() -> None:
    for text in (
        "https://example.com/v1?token=abcdef123456&x=1",
        "token=abcdef123456",
        "apikey: abcdef123456",
        "password='hunter2secret'",
        "secret=zxcvbnmasdfg",
    ):
        result = redact(text)
        assert "abcdef123456" not in result
        assert "hunter2secret" not in result
        assert "zxcvbnmasdfg" not in result
        assert MASK in result


def test_redact_standalone_sk_key() -> None:
    result = redact("provider said invalid key sk-proj-AAAABBBBCCCCDDDD")
    assert "sk-proj-AAAABBBBCCCCDDDD" not in result


def test_short_secret_is_fully_masked() -> None:
    assert redact("token=abcd") == "token=****"
    assert redact("token=ab") == "token=****"


def test_non_sensitive_keys_are_kept() -> None:
    """不能把 maxTokens / tokenCount 之类字段误脱敏。"""
    assert redact('{"maxTokens": 1024}') == '{"maxTokens": 1024}'
    assert redact_mapping({"maxTokens": 1024, "temperature": 0.7}) == {
        "maxTokens": 1024,
        "temperature": 0.7,
    }


def test_redact_mapping_recursive() -> None:
    payload = {
        "profile": {
            "id": "p1",
            "apiKey": "sk-abcdefgh1234",
            "headers": {"Authorization": "Bearer sk-abcdefgh1234"},
            "timeoutSeconds": 60,
        },
        "items": [{"password": "supersecret"}],
        "count": 2,
    }
    result = redact_mapping(payload)
    rendered = safe_repr(result)
    assert "sk-abcdefgh1234" not in rendered
    assert "supersecret" not in rendered
    assert result["profile"]["timeoutSeconds"] == 60
    assert result["count"] == 2
    assert result["profile"]["apiKey"] == "****1234"


def test_redact_handles_non_string_input() -> None:
    assert redact(None) == ""
    assert redact(1234) == "1234"


def test_long_text_is_truncated() -> None:
    result = redact("x" * 5000)
    assert len(result) < 5000
    assert "truncated" in result


def test_error_messages_are_redacted() -> None:
    """AiError 的 message 本身也必须脱敏。"""
    from app.errors import AiError

    error = AiError.internal("upstream said api_key=sk-verysecretvalue123")
    assert "sk-verysecretvalue123" not in str(error)
    assert "sk-verysecretvalue123" not in error.error_payload()["message"]


def test_http_error_details_are_redacted() -> None:
    from app.providers.base import _http_error_details

    details = _http_error_details('{"error":"bad key sk-abcdefghijkl"}')
    assert details
    assert "sk-abcdefghijkl" not in details[0].message
