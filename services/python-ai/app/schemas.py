"""pydantic 请求/响应模型（严格对齐契约 §0.1/§0.2/§7 字段名，camelCase）。

约定：
- 对象内部一律 camelCase，与 api-contract.md 及 llm-config.schema.json 一致。
- 请求模型默认 `extra="allow"`（Java 侧可能携带新字段），关键字段逐个校验。
"""

from __future__ import annotations

import json
import secrets
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Final, Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from .config import SERVICE_VERSION
from .errors import ErrorCategory, ErrorDetail

REQUEST_ID_PREFIX: Final[str] = "req_"
REQUEST_ID_HEX_LEN: Final[int] = 16

QuestionType = Literal[
    "single_choice",
    "multiple_choice",
    "true_false",
    "fill_blank",
    "short_answer",
    "essay",
]
ProviderName = Literal["openai-compatible", "ollama"]


def utc_now_iso() -> str:
    """UTC ISO-8601，形如 2026-10-10T14:00:00Z。"""
    return (
        datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def new_request_id() -> str:
    """`req_` + 16 位随机 hex（契约 §0.1）。"""
    return f"{REQUEST_ID_PREFIX}{secrets.token_hex(REQUEST_ID_HEX_LEN // 2)}"


# --------------------------------------------------------------------------------------
# 通用包络
# --------------------------------------------------------------------------------------


class ErrorBody(BaseModel):
    """契约 §0.2 error 对象。"""

    model_config = ConfigDict(extra="allow")

    code: str
    message: str
    details: list[dict[str, Any]] | None = None
    retryable: bool = False
    suggestion: str | None = None
    category: str | None = None

    @classmethod
    def from_details(
        cls, code: str, message: str, *, retryable: bool, details: list[ErrorDetail]
    ) -> "ErrorBody":
        return cls(
            code=code,
            message=message,
            details=[item.to_dict() for item in details] or None,
            retryable=retryable,
        )


class Envelope(BaseModel):
    """统一响应包络。"""

    model_config = ConfigDict(extra="allow")

    success: bool
    data: Any = None
    error: dict[str, Any] | None = None
    requestId: str
    timestamp: str

    @classmethod
    def ok(cls, data: Any, *, request_id: str) -> "Envelope":
        return cls(
            success=True,
            data=data,
            error=None,
            requestId=request_id,
            timestamp=utc_now_iso(),
        )

    @classmethod
    def fail(cls, error: dict[str, Any], *, request_id: str) -> "Envelope":
        return cls(
            success=False,
            data=None,
            error=error,
            requestId=request_id,
            timestamp=utc_now_iso(),
        )


# --------------------------------------------------------------------------------------
# 健康检查
# --------------------------------------------------------------------------------------


class HealthResponse(BaseModel):
    """契约 §7 GET /health（不鉴权）。"""

    status: Literal["UP"] = "UP"
    service: Literal["python-ai"] = "python-ai"
    version: str = SERVICE_VERSION
    providers: list[str] = Field(default_factory=list)


# --------------------------------------------------------------------------------------
# LLM 调用档案
# --------------------------------------------------------------------------------------


class LlmParameters(BaseModel):
    """llm-config.schema.json → profile.parameters（additionalProperties: false）。"""

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    temperature: float | None = Field(default=None, ge=0, le=2)
    top_p: float | None = Field(default=None, ge=0, le=1, alias="topP")
    max_tokens: int | None = Field(default=None, ge=1, le=131072, alias="maxTokens")
    stream: bool | None = None

    def to_api_kwargs(self) -> dict[str, Any]:
        """转换为供应商请求体字段（camelCase → OpenAI 字段名）。"""
        payload: dict[str, Any] = {}
        if self.temperature is not None:
            payload["temperature"] = self.temperature
        if self.top_p is not None:
            payload["top_p"] = self.top_p
        if self.max_tokens is not None:
            payload["max_tokens"] = self.max_tokens
        return payload


class LlmCallProfile(BaseModel):
    """契约 §7 LlmCallProfile。

    v1.1 起配置来自 `<UserData>/config/setting.json`，**不再有凭据存储**：
    `apiKey` 只随单次请求在内存中传递，Python 不落盘、不缓存、不写日志/异常消息。
    """

    model_config = ConfigDict(extra="ignore", populate_by_name=True)

    id: str = Field(min_length=1, max_length=64)
    provider: ProviderName
    base_url: str = Field(min_length=8, max_length=512, alias="baseUrl")
    model: str = Field(min_length=1, max_length=128)
    timeout_seconds: int = Field(default=60, ge=1, le=900, alias="timeoutSeconds")
    connect_timeout_seconds: int = Field(
        default=5, ge=1, le=120, alias="connectTimeoutSeconds"
    )
    allow_local_network: bool = Field(default=False, alias="allowLocalNetwork")
    parameters: LlmParameters = Field(default_factory=LlmParameters)
    api_key: str | None = Field(default=None, alias="apiKey")

    @field_validator("base_url")
    @classmethod
    def _strip_trailing_slash(cls, value: str) -> str:
        return value.strip().rstrip("/")

    @field_validator("api_key")
    @classmethod
    def _normalise_api_key(cls, value: str | None) -> str | None:
        """apiKey 传了就必须非空（本地 Ollama 可传任意非空字符串）。"""
        if value is None:
            return None
        text = value.strip()
        if not text:
            raise ValueError("apiKey 不能为空字符串；不需要密钥时请省略该字段")
        return text

    @property
    def profile_ref(self) -> str:
        """档案标识 `profile://<profileId>`（契约 §7）。

        语义是「这次调用用的是哪个档案」，**不是密钥也不是凭据引用**：
        与是否配置 `apiKey` 无关（没配密钥时同样是这个值），任何 provider 都一致。
        """
        return f"profile://{self.id}"

    @property
    def effective_max_tokens(self) -> int:
        """思考型模型（如 qwen3）max_tokens 过小会导致 content 为空，默认给 1024。"""
        return self.parameters.max_tokens or 1024

    @property
    def httpx_timeout(self) -> httpx.Timeout:
        return httpx.Timeout(
            timeout=float(self.timeout_seconds),
            connect=float(self.connect_timeout_seconds),
        )


# --------------------------------------------------------------------------------------
# /api/v1/ai/explain
# --------------------------------------------------------------------------------------


class ExplainQuestion(BaseModel):
    """待讲解题目（内容全部视为不可信输入）。"""

    model_config = ConfigDict(extra="allow")

    stem: str = Field(min_length=1, max_length=20000)
    question_type: str | None = Field(default=None, alias="questionType")
    options: Any = None
    answer: Any = None
    explanation: str | None = None


MAX_REFERENCE_CONTENT_CHARS: Final[int] = 2000
MAX_REFERENCE_TITLE_CHARS: Final[int] = 200
MAX_REFERENCE_REF_ID_CHARS: Final[int] = 64


def _coerce_text(value: Any) -> str:
    """把任意 JSON 值归一为文本（供不可信字段容错，绝不抛异常）。"""
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (dict, list)):
        try:
            return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        except (TypeError, ValueError):  # pragma: no cover - JSON 值必然可序列化
            return str(value)
    return str(value)


class Reference(BaseModel):
    """本地检索资料分块（契约 §11.3）：`content` 是**不可信输入**。

    设计取舍：
    - `refId` 必须非空——它是模型引用资料的唯一凭据，也是 Java 侧复核 `chunkId` 的锚点；
    - `title`/`content` 超长一律**截断**而不是报错：422 会打断整次讲解，
      截断只损失尾部文本，且保证提示词体积可控（见 MAX_REFERENCE_CONTENT_CHARS）；
    - `pageNo` 不可解析时归一为 `None`，引用仍可凭 `refId` + `title` 追溯；
    - 未知字段策略与其它请求模型一致：`extra="allow"`。
    """

    model_config = ConfigDict(extra="allow", populate_by_name=True)

    ref_id: str = Field(alias="refId", min_length=1, max_length=MAX_REFERENCE_REF_ID_CHARS)
    title: str = Field(default="")
    page_no: int | None = Field(default=None, alias="pageNo")
    content: str = Field(default="")

    @field_validator("ref_id")
    @classmethod
    def _normalise_ref_id(cls, value: str) -> str:
        """必填且非空；顺带压缩空白，保证与提示词 label 里的 refId 完全一致。

        换行会让 `label=资料[K1]` 结构断裂（可能被用来伪造提示词结构），
        因此这里把所有空白压成单个空格；断言仍要求非空。
        """
        text = " ".join(value.split())
        if not text:
            raise ValueError("refId 不能为空字符串")
        return text

    @field_validator("title", "content", mode="before")
    @classmethod
    def _coerce_untrusted_text(cls, value: Any) -> str:
        return _coerce_text(value)

    @field_validator("content", mode="after")
    @classmethod
    def _truncate_content(cls, value: str) -> str:
        """超长资料截断到上限（幂等：已在上限内的内容不再变化）。"""
        return value[:MAX_REFERENCE_CONTENT_CHARS]

    @field_validator("title", mode="after")
    @classmethod
    def _truncate_title(cls, value: str) -> str:
        return value[:MAX_REFERENCE_TITLE_CHARS]

    @field_validator("page_no", mode="before")
    @classmethod
    def _coerce_page_no(cls, value: Any) -> int | None:
        """页码容错：非数字/负数/布尔一律视为未知，不因此拒绝整个请求。"""
        if value is None or isinstance(value, bool):
            return None
        if isinstance(value, int):
            return value if value >= 0 else None
        if isinstance(value, str):
            text = value.strip()
            if text.isdigit():
                return int(text)
        return None


class Citation(BaseModel):
    """讲解引用的资料（契约 §7/§11.3）：`refId` 必须来自本次请求提供的集合。"""

    model_config = ConfigDict(extra="allow", populate_by_name=True)

    ref_id: str = Field(alias="refId", min_length=1, max_length=MAX_REFERENCE_REF_ID_CHARS)
    used: bool = False


class ExplainRequest(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    question: ExplainQuestion
    user_answer: Any = Field(default=None, alias="userAnswer")
    # 本地检索结果（阶段 6，契约 §11.3）：全部视为不可信输入，缺省时行为与阶段 5 完全一致
    references: list[Reference] | None = Field(default=None, max_length=50)
    profile: LlmCallProfile
    locale: str = Field(default="zh-CN", max_length=32)


class ExplainResponse(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    explanation: str
    tips: list[str] = Field(default_factory=list)
    # 只包含本次实际提供的 refId（已按提供集合过滤，绝不凭空产生）
    citations: list[Citation] = Field(default_factory=list)
    # 检索片段命中可疑指令模式的标记（仅标记与日志，不改变主流程）
    injection_suspected: bool = Field(default=False, alias="injectionSuspected")
    model: str
    usage: dict[str, Any] = Field(default_factory=dict)
    latency_ms: int = Field(alias="latencyMs")
    # 本次调用使用的档案标识（profile://<profileId>）：不是密钥也不是凭据引用
    profile_ref: str | None = Field(default=None, alias="profileRef")


# --------------------------------------------------------------------------------------
# /api/v1/ai/grade-subjective
# --------------------------------------------------------------------------------------


class RubricCriterion(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    id: str = Field(min_length=1, max_length=64)
    description: str = Field(default="", alias="desc", max_length=2000)
    weight: float = Field(default=0.0, ge=0, le=1000)

    @model_validator(mode="before")
    @classmethod
    def _accept_desc_alias(cls, value: Any) -> Any:
        if isinstance(value, dict) and "desc" not in value and "description" in value:
            return {**value, "desc": value["description"]}
        return value


class Rubric(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    criteria: list[RubricCriterion] = Field(default_factory=list)
    max_score: float | None = Field(default=None, ge=0, alias="maxScore")


class GradeQuestion(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    stem: str = Field(min_length=1, max_length=20000)
    reference_answer: str | None = Field(default=None, alias="referenceAnswer", max_length=20000)
    rubric: Rubric | None = None
    question_type: str | None = Field(default=None, alias="questionType")


class GradeSubjectiveRequest(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    question: GradeQuestion
    user_answer: str = Field(alias="userAnswer", max_length=20000)
    profile: LlmCallProfile
    max_score: float = Field(alias="maxScore", gt=0, le=1000)
    locale: str = Field(default="zh-CN", max_length=32)


class GradeCriterionResult(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str
    score: float
    reason: str = ""


class GradeSubjectiveResponse(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    suggested_score: float = Field(alias="suggestedScore")
    max_score: float = Field(alias="maxScore")
    criteria: list[GradeCriterionResult] = Field(default_factory=list)
    confidence: float = Field(ge=0, le=1)
    needs_review: Literal[True] = Field(default=True, alias="needsReview")
    model: str
    latency_ms: int = Field(alias="latencyMs")
    # 本次调用使用的档案标识（profile://<profileId>）：不是密钥也不是凭据引用
    profile_ref: str | None = Field(default=None, alias="profileRef")


# --------------------------------------------------------------------------------------
# /api/v1/ai/test-connection
# --------------------------------------------------------------------------------------


class TestConnectionRequest(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    profile: LlmCallProfile
    api_key: str | None = Field(default=None, alias="apiKey")

    @field_validator("api_key")
    @classmethod
    def _normalise_api_key(cls, value: str | None) -> str | None:
        if value is None:
            return None
        text = value.strip()
        if not text:
            raise ValueError("apiKey 不能为空字符串")
        return text


class TestConnectionResponse(BaseModel):
    model_config = ConfigDict(extra="allow", populate_by_name=True)

    ok: bool
    category: ErrorCategory
    latency_ms: int = Field(alias="latencyMs")
    http_status: int | None = Field(default=None, alias="httpStatus")
    model_echo: str | None = Field(default=None, alias="modelEcho")
    message: str
    suggestion: str


# --------------------------------------------------------------------------------------
# /api/v1/ai/profiles/{id}/probe
# --------------------------------------------------------------------------------------


class ProbeCapabilities(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    chat_completions: bool = Field(default=False, alias="chatCompletions")
    models_endpoint: bool = Field(default=False, alias="modelsEndpoint")


class ProbeResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    profile_id: str = Field(alias="profileId")
    reachable: bool
    models: list[str] = Field(default_factory=list)
    capabilities: ProbeCapabilities = Field(default_factory=ProbeCapabilities)


class ProbeQuery(BaseModel):
    """probe 的查询参数（不使用密钥，可复用 Java 转发的档案信息）。"""

    model_config = ConfigDict(extra="allow", populate_by_name=True)

    provider: ProviderName = "openai-compatible"
    base_url: str = Field(alias="baseUrl", min_length=8, max_length=512)
    model: str | None = None
    timeout_seconds: int = Field(default=10, ge=1, le=900, alias="timeoutSeconds")
    connect_timeout_seconds: int = Field(
        default=5, ge=1, le=120, alias="connectTimeoutSeconds"
    )
    allow_local_network: bool = Field(default=False, alias="allowLocalNetwork")
