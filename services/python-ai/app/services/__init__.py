"""AI 能力服务层：/explain 与 /grade-subjective 的业务编排。"""

from .explain_service import (
    ExplainOutcome,
    coerce_citations,
    provided_ref_ids,
    run_explain,
)
from .grading import GradingOutcome, run_subjective_grading
from .json_parse import extract_json_object
from .prompts import (
    build_explain_prompt,
    build_grade_prompt,
    escape_untrusted_text,
    format_references,
    wrap_untrusted,
)

__all__ = [
    "ExplainOutcome",
    "GradingOutcome",
    "build_explain_prompt",
    "build_grade_prompt",
    "coerce_citations",
    "escape_untrusted_text",
    "extract_json_object",
    "format_references",
    "provided_ref_ids",
    "run_explain",
    "run_subjective_grading",
    "wrap_untrusted",
]

