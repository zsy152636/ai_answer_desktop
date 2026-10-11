"""提示注入可疑模式检测（契约 §7/§11.3、开发文档 §6.4）。

设计意图与取舍：
- 本地检索到的资料分块（`references[].content`）是**不可信输入**，可能夹带
  针对大模型的指令（「忽略以上要求」「You are now …」「system:」等）。
- 本模块**只做标记，不改写、不丢弃资料**：内容仍原样作为数据交给模型，
  防护由「固定边界转义 + 系统提示 + 检测标记」三层共同完成
  （边界转义见 `services/prompts.py`）。
- 命中后写入日志的摘要**只含模式名与命中计数，绝不含原文**；摘要统一再过一遍
  `security.redaction.redact`，避免将来有人往摘要里拼原文时泄漏。
- 正则一律大小写不敏感（中文本身无大小写，英文用 IGNORECASE），覆盖中英文常见形态。
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from typing import Any, Final

from .redaction import redact

# (模式名, 正则) —— 模式名是安全常量，会出现在日志摘要里，绝不含用户内容。
INJECTION_PATTERNS: Final[tuple[tuple[str, re.Pattern[str]], ...]] = (
    # 1) 英文：忽略之前的指令
    (
        "ignore_previous",
        re.compile(
            r"ignore\s+(?:all\s+)?(?:of\s+)?(?:the\s+)?"
            r"(?:previous|preceding|above|prior|earlier|foregoing)\s+"
            r"(?:instruction|prompt|rule|context|message)s?",
            re.IGNORECASE,
        ),
    ),
    # 2) 英文：无视以上内容
    (
        "disregard_above",
        re.compile(
            r"disregard\s+(?:all\s+)?(?:of\s+)?(?:the\s+)?"
            r"(?:above|previous|preceding|prior|earlier|foregoing|rules?|instructions?)",
            re.IGNORECASE,
        ),
    ),
    # 3) 英文：忘记你的指令
    (
        "forget_instructions",
        re.compile(
            r"forget\s+(?:everything|all|any)\b|"
            r"forget\s+(?:your\s+)?(?:previous\s+|earlier\s+)?"
            r"(?:instruction|prompt|rule|training)s?",
            re.IGNORECASE,
        ),
    ),
    # 4) 中文：忽略/无视以上（之前的）指令
    (
        "ignore_zh",
        re.compile(
            r"(?:忽略|无视|不要理会|不用管|忘掉|忘记)\s*"
            r"(?:以上|上述|上面|前面|之前|先前|所有|全部|此前|这些|那些)?\s*"
            r"(?:的)?\s*(?:所有|全部|一切)?\s*"
            r"(?:指令|指示|要求|规则|设定|提示词|提示语|命令|限制|内容|对话)"
        ),
    ),
    # 5) 中文：角色替换（你现在是…）
    (
        "role_override_zh",
        re.compile(
            r"(?:你现在是|你现在开始是|从现在开始你是|从现在起你是|你要扮演|请你扮演|"
            r"你的新身份是|你的新角色是|你不再是|忘记你是)"
        ),
    ),
    # 6) 英文：角色替换
    (
        "you_are_now",
        re.compile(
            r"you\s+are\s+now\b|from\s+now\s+on\s*,?\s*you\s+(?:are|will\s+be)\b|"
            r"(?:act|behave)\s+as\s+(?:a|an|the)?\s*(?:system|assistant|ai|dan|jailbroken)\b",
            re.IGNORECASE,
        ),
    ),
    # 7) 伪系统标记：<system> / [system] / <|im_start|> 等特殊 token / 行首 system: 指令
    #    行首 `system:` 需紧跟指令性措辞才算命中，避免把「System: Linux 6.1」这类
    #    正常配置行当成注入（标记只作提示，但仍尽量减少误报噪音）。
    (
        "system_marker",
        re.compile(
            r"<\s*/?\s*system\s*>|\[\s*system\s*\]|<\|[^|>\n]{0,16}?\|>|"
            r"(?:^|\n)\s*(?:system|系统)\s*[:：]\s*"
            r"(?:你|您|请|必须|务必|从现在|现在起|忽略|无视|不要|不需|只能|只|仅仅|"
            r"you|please|must|always|only|ignore|disregard|forget|do\s+not|never|"
            r"reply|output|answer|act|pretend|new)",
            re.IGNORECASE,
        ),
    ),
    # 8) 要求照抄输出指定内容
    (
        "output_following",
        re.compile(
            r"(?:output|print|reply\s+with|respond\s+with)\s+the\s+following|"
            r"(?:(?:请|必须|直接|只|一定|务必)\s*){0,3}(?:输出|打印|回复|回答)\s*"
            r"(?:以下|下面|如下|这些|这个)(?:的)?\s*(?:内容|文字|文本|答案|句子)",
            re.IGNORECASE,
        ),
    ),
    # 9) 套取系统提示词（要求出现“你的/系统”所属关系，降低正常教材的误报）
    (
        "prompt_leak",
        re.compile(
            r"(?:reveal|show|print|repeat|disclose|expose|tell\s+me)\s+(?:me\s+)?(?:your\s+)?"
            r"(?:system\s+|hidden\s+|original\s+)?(?:prompt|instructions?|rules?)|"
            r"(?:告诉|展示|显示|重复|输出|打印|复述)\s*(?:我)?\s*"
            r"(?:你的|系统)\s*(?:系统)?(?:提示词|提示语|指令|设定|规则)|"
            r"(?:你的|系统)\s*(?:系统)?(?:提示词|提示语)",
            re.IGNORECASE,
        ),
    ),
    # 10) 越狱 / 开发者模式
    (
        "jailbreak_mode",
        re.compile(
            r"(?:developer|dev|debug|dan|jailbreak|越狱|开发者|上帝)\s*(?:mode|模式)|"
            r"jailbreak|do\s+anything\s+now",
            re.IGNORECASE,
        ),
    ),
    # 11) 伪造“新指令”段落
    (
        "new_instructions",
        re.compile(
            r"(?:new|updated|revised|real)\s+instructions?\s*[:：]|"
            r"(?:新的|更新后的|真正的|真实的)\s*(?:指令|指示|规则|要求)\s*[:：]",
            re.IGNORECASE,
        ),
    ),
)


@dataclass(frozen=True, slots=True)
class InjectionHit:
    """单个模式的命中情况（只有模式名与计数，不含原文）。"""

    pattern: str
    count: int


@dataclass(frozen=True, slots=True)
class InjectionScan:
    """检测结果（只有模式名与计数，不含原文）。"""

    hits: tuple[InjectionHit, ...] = ()
    scanned_chars: int = 0

    @property
    def suspected(self) -> bool:
        """是否命中任一可疑模式。"""
        return bool(self.hits)

    @property
    def hit_count(self) -> int:
        """命中总次数。"""
        return sum(hit.count for hit in self.hits)

    @property
    def pattern_names(self) -> tuple[str, ...]:
        """命中的模式名（去重、保持模式表顺序）。"""
        return tuple(hit.pattern for hit in self.hits)

    def redacted_summary(self) -> str:
        """可安全写入日志的摘要：数量 + 模式名，且再经脱敏兜底。"""
        detail = ",".join(f"{hit.pattern}x{hit.count}" for hit in self.hits) or "none"
        return redact(
            f"suspected={self.suspected} hits={self.hit_count} "
            f"chars={self.scanned_chars} patterns=[{detail}]"
        )


def scan_texts(texts: Iterable[str]) -> InjectionScan:
    """扫描若干文本，返回命中统计（只读，不修改入参）。"""
    counts: dict[str, int] = {}
    scanned_chars = 0
    for text in texts:
        if not isinstance(text, str) or not text:
            continue
        scanned_chars += len(text)
        for name, pattern in INJECTION_PATTERNS:
            hits = sum(1 for _ in pattern.finditer(text))
            if hits:
                counts[name] = counts.get(name, 0) + hits
    hits = tuple(
        InjectionHit(pattern=name, count=counts[name])
        for name, _pattern in INJECTION_PATTERNS
        if name in counts
    )
    return InjectionScan(hits=hits, scanned_chars=scanned_chars)


def scan_references(references: Sequence[Any] | None) -> InjectionScan:
    """扫描检索资料（标题 + 正文）。

    只读且**不做任何改写**：命中仅用于置位 `injectionSuspected` 与写脱敏日志，
    资料依旧原样交给模型（契约 §11.3 第 3 条）。
    采用 `getattr` 鸭子类型，避免安全层反向依赖 pydantic 模型。
    """
    if not references:
        return InjectionScan()
    texts: list[str] = []
    for reference in references:
        title = getattr(reference, "title", "") or ""
        content = getattr(reference, "content", "") or ""
        texts.append(title if isinstance(title, str) else str(title))
        texts.append(content if isinstance(content, str) else str(content))
    return scan_texts(texts)


__all__ = [
    "INJECTION_PATTERNS",
    "InjectionHit",
    "InjectionScan",
    "scan_references",
    "scan_texts",
]
