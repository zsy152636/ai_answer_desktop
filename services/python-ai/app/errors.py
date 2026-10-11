"""统一错误分类与异常。

两层分类：
1. `ErrorCode`：契约 §0.3 冻结的业务错误码（对 Java/前端可见）。
2. `ErrorCategory`：契约 §7 test-connection 的供应商故障分类
   （ok|dns|tls|auth|model_not_found|quota|timeout|protocol|blocked|unavailable）。

所有对外消息都经 redaction 脱敏，绝不携带请求头、密钥或供应商原始响应全文。
"""

from __future__ import annotations

import enum
from dataclasses import dataclass, field
from typing import Any

from .security.redaction import redact, safe_repr


class ErrorCode(str, enum.Enum):
    """契约 §0.3 错误码（v1 冻结）。"""

    VALIDATION_ERROR = "VALIDATION_ERROR"
    UNAUTHORIZED = "UNAUTHORIZED"
    ORIGIN_REJECTED = "ORIGIN_REJECTED"
    RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND"
    STATE_CONFLICT = "STATE_CONFLICT"
    VERSION_CONFLICT = "VERSION_CONFLICT"
    IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT"
    DB_BUSY = "DB_BUSY"
    DB_MIGRATION_FAILED = "DB_MIGRATION_FAILED"
    CONFIG_JSON_INVALID = "CONFIG_JSON_INVALID"
    CONFIG_SCHEMA_UNSUPPORTED = "CONFIG_SCHEMA_UNSUPPORTED"
    BASE_URL_BLOCKED = "BASE_URL_BLOCKED"
    # 契约 §0.3 冻结错误码（v1.1 语义已改为「该 profile 未配置 apiKey」，
    # 由 Java 依 config/setting.json 判定）；Python 侧不主动抛出，仅与契约枚举保持对齐。
    CREDENTIAL_MISSING = "CREDENTIAL_MISSING"
    LLM_AUTH_FAILED = "LLM_AUTH_FAILED"
    LLM_CONNECT_TIMEOUT = "LLM_CONNECT_TIMEOUT"
    LLM_UNAVAILABLE = "LLM_UNAVAILABLE"
    TASK_RETRY_EXHAUSTED = "TASK_RETRY_EXHAUSTED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class ErrorCategory(str, enum.Enum):
    """连接测试/调用故障分类（契约 §7）。"""

    OK = "ok"
    DNS = "dns"
    TLS = "tls"
    AUTH = "auth"
    MODEL_NOT_FOUND = "model_not_found"
    QUOTA = "quota"
    TIMEOUT = "timeout"
    PROTOCOL = "protocol"
    BLOCKED = "blocked"
    UNAVAILABLE = "unavailable"


HTTP_STATUS_BY_CODE: dict[ErrorCode, int] = {
    ErrorCode.VALIDATION_ERROR: 400,
    ErrorCode.UNAUTHORIZED: 401,
    ErrorCode.ORIGIN_REJECTED: 403,
    ErrorCode.RESOURCE_NOT_FOUND: 404,
    ErrorCode.STATE_CONFLICT: 409,
    ErrorCode.VERSION_CONFLICT: 409,
    ErrorCode.IDEMPOTENCY_CONFLICT: 409,
    ErrorCode.DB_BUSY: 503,
    ErrorCode.DB_MIGRATION_FAILED: 500,
    ErrorCode.CONFIG_JSON_INVALID: 400,
    ErrorCode.CONFIG_SCHEMA_UNSUPPORTED: 400,
    ErrorCode.BASE_URL_BLOCKED: 400,
    ErrorCode.CREDENTIAL_MISSING: 400,
    ErrorCode.LLM_AUTH_FAILED: 502,
    ErrorCode.LLM_CONNECT_TIMEOUT: 504,
    ErrorCode.LLM_UNAVAILABLE: 503,
    ErrorCode.TASK_RETRY_EXHAUSTED: 409,
    ErrorCode.INTERNAL_ERROR: 500,
}

# 供应商故障分类 → 契约错误码
CODE_BY_CATEGORY: dict[ErrorCategory, ErrorCode] = {
    ErrorCategory.DNS: ErrorCode.LLM_UNAVAILABLE,
    ErrorCategory.TLS: ErrorCode.LLM_UNAVAILABLE,
    ErrorCategory.AUTH: ErrorCode.LLM_AUTH_FAILED,
    ErrorCategory.MODEL_NOT_FOUND: ErrorCode.LLM_UNAVAILABLE,
    ErrorCategory.QUOTA: ErrorCode.LLM_UNAVAILABLE,
    ErrorCategory.TIMEOUT: ErrorCode.LLM_CONNECT_TIMEOUT,
    ErrorCategory.PROTOCOL: ErrorCode.LLM_UNAVAILABLE,
    ErrorCategory.BLOCKED: ErrorCode.BASE_URL_BLOCKED,
    ErrorCategory.UNAVAILABLE: ErrorCode.LLM_UNAVAILABLE,
}

# 各类别的默认用户提示与整改建议（面向用户，中文）
MESSAGE_BY_CATEGORY: dict[ErrorCategory, str] = {
    ErrorCategory.DNS: "无法解析模型服务域名",
    ErrorCategory.TLS: "TLS 握手失败或证书不可信",
    ErrorCategory.AUTH: "模型服务鉴权失败",
    ErrorCategory.MODEL_NOT_FOUND: "模型不存在或未拉取",
    ErrorCategory.QUOTA: "额度不足或请求过于频繁",
    ErrorCategory.TIMEOUT: "连接模型服务超时",
    ErrorCategory.PROTOCOL: "模型服务返回内容不符合预期协议",
    ErrorCategory.BLOCKED: "目标地址被安全策略拒绝",
    ErrorCategory.UNAVAILABLE: "AI 服务或模型不可用",
}

SUGGESTION_BY_CATEGORY: dict[ErrorCategory, str] = {
    ErrorCategory.OK: "连接正常，可继续用于答题与判分。",
    ErrorCategory.DNS: "检查 baseUrl 的域名拼写与本机 DNS/网络连通性。",
    ErrorCategory.TLS: "检查是否为企业代理证书，或改用 https 证书可信的地址。",
    ErrorCategory.AUTH: "检查 config/setting.json 中该档案的 apiKey 是否有效且未被撤销。",
    ErrorCategory.MODEL_NOT_FOUND: "确认模型名称，本地 Ollama 可先执行 ollama pull。",
    ErrorCategory.QUOTA: "检查账户余额与限流策略，稍后重试。",
    ErrorCategory.TIMEOUT: "确认端点可达，或调大 connectTimeoutSeconds/timeoutSeconds。",
    ErrorCategory.PROTOCOL: "确认服务端为 OpenAI 兼容协议，或改选 ollama provider。",
    ErrorCategory.BLOCKED: "该地址属于 loopback/私网/链路本地/云元数据，需显式开启“允许本地网络”。",
    ErrorCategory.UNAVAILABLE: "确认模型服务已启动，AI 能力降级不影响答题主流程。",
}

RETRYABLE_CATEGORIES: frozenset[ErrorCategory] = frozenset(
    {
        ErrorCategory.DNS,
        ErrorCategory.TIMEOUT,
        ErrorCategory.QUOTA,
        ErrorCategory.UNAVAILABLE,
    }
)


@dataclass(slots=True)
class ErrorDetail:
    """字段级错误明细。"""

    field: str
    message: str

    def to_dict(self) -> dict[str, str]:
        return {"field": self.field, "message": redact(self.message)}


@dataclass(slots=True)
class AiError(Exception):
    """统一异常：业务错误码 + 供应商故障分类 + 面向用户的提示。"""

    code: ErrorCode
    message: str
    category: ErrorCategory = ErrorCategory.UNAVAILABLE
    http_status: int | None = None
    retryable: bool | None = None
    suggestion: str | None = None
    details: list[ErrorDetail] = field(default_factory=list)
    cause: BaseException | None = field(default=None, repr=False)

    def __post_init__(self) -> None:
        # 异常 message 也必须脱敏，避免供应商原文或密钥进入日志/响应
        self.message = redact(self.message)
        if self.http_status is None:
            self.http_status = HTTP_STATUS_BY_CODE.get(self.code, 500)
        if self.retryable is None:
            self.retryable = self.category in RETRYABLE_CATEGORIES
        Exception.__init__(self, self.message)

    @classmethod
    def from_category(
        cls,
        category: ErrorCategory,
        *,
        message: str | None = None,
        http_status: int | None = None,
        details: list[ErrorDetail] | None = None,
        cause: BaseException | None = None,
    ) -> "AiError":
        """按供应商故障分类生成异常，自动选择契约错误码与提示。"""
        code = CODE_BY_CATEGORY.get(category, ErrorCode.LLM_UNAVAILABLE)
        return cls(
            code=code,
            message=message or MESSAGE_BY_CATEGORY.get(category, "AI 服务调用失败"),
            category=category,
            http_status=http_status,
            suggestion=SUGGESTION_BY_CATEGORY.get(category),
            details=details or [],
            cause=cause,
        )

    @classmethod
    def unauthorised(cls, message: str = "内部令牌缺失或无效") -> "AiError":
        return cls(
            code=ErrorCode.UNAUTHORIZED,
            message=message,
            category=ErrorCategory.AUTH,
            http_status=401,
            retryable=False,
        )

    @classmethod
    def validation(
        cls, message: str, details: list[ErrorDetail] | None = None
    ) -> "AiError":
        return cls(
            code=ErrorCode.VALIDATION_ERROR,
            message=message,
            category=ErrorCategory.PROTOCOL,
            http_status=400,
            retryable=False,
            details=details or [],
        )

    @classmethod
    def blocked(cls, message: str) -> "AiError":
        return cls(
            code=ErrorCode.BASE_URL_BLOCKED,
            message=message,
            category=ErrorCategory.BLOCKED,
            http_status=400,
            retryable=False,
            suggestion=SUGGESTION_BY_CATEGORY[ErrorCategory.BLOCKED],
        )

    @classmethod
    def internal(cls, message: str = "服务器内部错误", cause: BaseException | None = None) -> "AiError":
        return cls(
            code=ErrorCode.INTERNAL_ERROR,
            message=message,
            category=ErrorCategory.UNAVAILABLE,
            http_status=500,
            retryable=False,
            cause=cause,
        )

    def error_payload(self) -> dict[str, Any]:
        """契约 §0.2 error 对象。"""
        payload: dict[str, Any] = {
            "code": self.code.value,
            "message": redact(self.message),
            "retryable": bool(self.retryable),
        }
        if self.details:
            payload["details"] = [item.to_dict() for item in self.details]
        if self.suggestion:
            payload["suggestion"] = redact(self.suggestion)
        if self.category is not ErrorCategory.UNAVAILABLE:
            payload["category"] = self.category.value
        return payload

    def __str__(self) -> str:  # pragma: no cover - 仅用于日志
        return f"{self.code.value}/{self.category.value}: {self.message}"


def classify_http_status(
    status: int, body: str = "", *, model: str | None = None
) -> ErrorCategory:
    """把供应商 HTTP 状态码 + 响应体片段映射为故障分类。"""
    text = (body or "").lower()
    if status in (401, 403):
        return ErrorCategory.AUTH
    if status == 404:
        if "model" in text:
            return ErrorCategory.MODEL_NOT_FOUND
        return ErrorCategory.PROTOCOL
    if status == 429:
        return ErrorCategory.QUOTA
    if status in (402,):
        return ErrorCategory.QUOTA
    if status in (408, 504):
        return ErrorCategory.TIMEOUT
    if status >= 500:
        return ErrorCategory.UNAVAILABLE
    if status == 400:
        if "model" in text and ("not found" in text or "unknown" in text or "不存在" in text):
            return ErrorCategory.MODEL_NOT_FOUND
        return ErrorCategory.PROTOCOL
    if 200 <= status < 300:
        return ErrorCategory.OK
    return ErrorCategory.PROTOCOL


def safe_exception_text(exc: BaseException) -> str:
    """异常的安全文本（用于日志），始终脱敏。"""
    return redact(f"{type(exc).__name__}: {safe_repr(exc)}")
