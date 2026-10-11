# -*- coding: utf-8 -*-
"""生成《功能状态与系统流程图》所需的 6 张流程图（PNG）。

硬约束（与交付要求一致）：
  * 只用 Pillow；中文字体显式指定 C:\\Windows\\Fonts\\msyh.ttc（缺失时退到 simhei.ttf）；
  * 画布宽度 1220 px，按 200 DPI 换算约 15.49 cm，落在 A4 版心宽度（15.5 cm）之内；
  * 每个框都有实际含义，箭头方向可读出流程；脚本末尾做「框越界 + 文字溢出框体」自检。

可重复运行：python draw_flowcharts.py
"""

from __future__ import annotations

import math
import os
import sys
from dataclasses import dataclass, field

from PIL import Image, ImageDraw, ImageFont

# --------------------------------------------------------------------------- 基础配置

HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = HERE

# 200 DPI 下 A4 版心宽度 15.5 cm 约为 1220.5 px；取 1220 保证插进 Word 后不越界。
CANVAS_W = 1220
DPI = 200

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
]
FONT_PATH = next((p for p in FONT_CANDIDATES if os.path.isfile(p)), None)
if FONT_PATH is None:
    raise SystemExit("找不到中文字体：msyh.ttc / simhei.ttf")

INK = (33, 37, 41)
SUB = (95, 104, 115)
EDGE = (122, 132, 142)
PAPER = (255, 255, 255)

KIND_FILL = {
    "shell": ((232, 240, 254), (144, 168, 214)),
    "core": ((232, 245, 233), (129, 176, 137)),
    "ai": ((255, 248, 225), (196, 168, 96)),
    "store": ((236, 239, 241), (150, 160, 170)),
    "ext": ((253, 235, 235), (200, 140, 140)),
    "plain": ((255, 255, 255), (160, 170, 180)),
}
WARN = (176, 0, 32)
AMBER = (176, 106, 20)
OK = (21, 101, 60)

W = 373          # 三列时每列宽度
GAP = 20         # 列间距
M = 30           # 画布左右留白
COL = [M + i * (W + GAP) for i in range(3)]   # 30 / 423 / 816
BOX_H = 132      # 统一框高
ROW_PITCH = 158  # 统一行距（留出 26px 箭头空间，避免箭头与框体、文字相撞）
TAG_SIZE = 13    # 行间标签字号

_font_cache: dict[int, ImageFont.FreeTypeFont] = {}


def font(size: int) -> ImageFont.FreeTypeFont:
    if size not in _font_cache:
        _font_cache[size] = ImageFont.truetype(FONT_PATH, size, index=0)
    return _font_cache[size]


def wrap(text: str, f: ImageFont.FreeTypeFont, max_px: int) -> list[str]:
    """按像素宽度换行；中文逐字符，ASCII 连续段（英文/数字/路径）不拆开。"""
    if not text:
        return []
    lines: list[str] = []
    for raw in text.split("\n"):
        if raw == "":
            lines.append("")
            continue
        current = ""
        token = ""
        for ch in raw:
            token += ch
            if ord(ch) < 128 and ch != " ":
                continue
            piece, token = token, ""
            if f.getlength(current + piece) <= max_px:
                current += piece
            else:
                if current:
                    lines.append(current)
                while piece and f.getlength(piece) > max_px:
                    cut = piece
                    while cut and f.getlength(cut) > max_px:
                        cut = cut[:-1]
                    if not cut:
                        break
                    lines.append(cut)
                    piece = piece[len(cut):]
                current = piece
        if token:
            if f.getlength(current + token) <= max_px:
                current += token
            else:
                if current:
                    lines.append(current)
                current = token
        if current:
            lines.append(current)
    return lines


def text_h(lines: list[str], f: ImageFont.FreeTypeFont, gap: int) -> int:
    return 0 if not lines else len(lines) * (f.size + gap) - gap


@dataclass
class Box:
    x: int
    y: int
    w: int
    h: int
    title: str = ""
    body: list[str] = field(default_factory=list)
    kind: str = "plain"
    font_size: int = 17
    title_size: int = 22
    radius: int = 10
    align: str = "center"
    dash: bool = False

    @property
    def cx(self) -> int:
        return self.x + self.w // 2

    @property
    def cy(self) -> int:
        return self.y + self.h // 2

    @property
    def right(self) -> int:
        return self.x + self.w

    @property
    def bottom(self) -> int:
        return self.y + self.h


class Canvas:
    def __init__(self, height: int, title: str, subtitle: str = ""):
        self.w = CANVAS_W
        self.h = height
        self.img = Image.new("RGB", (self.w, height), PAPER)
        self.d = ImageDraw.Draw(self.img)
        self.boxes: list[Box] = []
        self.overflow: list[str] = []
        # 所有实际落笔的文字包围盒：(group, x0, y0, x1, y1, text)
        # group 用来区分「同一个框的标题与正文」（同一 group 内允许紧邻），
        # 不同 group 的文字一旦像素相交就是缺陷。
        self.texts: list[tuple[str, int, int, int, int, str]] = []
        self.uid = 0
        self.d.text((M, 20), title, font=font(30), fill=INK, anchor="la")
        self._record("canvas-title", (M, 20), title, font(30), "la")
        if subtitle:
            self.d.text((M + 2, 58), subtitle, font=font(17), fill=SUB, anchor="la")
            self._record("canvas-subtitle", (M + 2, 58), subtitle, font(17), "la")

    # ---------------------------------------------------------------- 文字登记与自检
    def _bbox(self, xy, text, f, anchor):
        w = f.getlength(text)
        h = f.size
        x, y = xy
        if anchor == "ma":
            x0, y0 = x - w / 2, y
        elif anchor == "mm":
            x0, y0 = x - w / 2, y - h / 2
        elif anchor == "rm":
            x0, y0 = x - w, y - h / 2
        else:
            x0, y0 = x, y
        return round(x0), round(y0), round(x0 + w), round(y0 + h)

    def _record(self, group: str, xy, text: str, f, anchor: str):
        if not text:
            return
        x0, y0, x1, y1 = self._bbox(xy, text, f, anchor)
        self.texts.append((group, x0, y0, x1, y1, text))

    def _draw_text(self, group: str, xy, text: str, f, fill, anchor: str):
        self.d.text(xy, text, font=f, fill=fill, anchor=anchor)
        self._record(group, xy, text, f, anchor)

    def _next_group(self) -> str:
        self.uid += 1
        return f"g{self.uid}"

    def add_text(self, group: str, xy, text: str, f, anchor: str):
        """只登记文字包围盒，不落笔（用于给手动摆放的文字补登记）。"""
        self._record(group, xy, text, f, anchor)

    def text_group(self) -> str:
        return self._next_group()

    @staticmethod
    def _intersect(a, b, tol: int = 1):
        x0 = max(a[1], b[1]) + tol
        y0 = max(a[2], b[2]) + tol
        x1 = min(a[3], b[3]) - tol
        y1 = min(a[4], b[4]) - tol
        if x1 <= x0 or y1 <= y0:
            return None
        return x1 - x0, y1 - y0

    def text_collisions(self) -> list[str]:
        """不同 group 的文字包围盒两两求交；相交即记为缺陷（含 1px 容差）。"""
        problems = []
        items = self.texts
        for i in range(len(items)):
            for j in range(i + 1, len(items)):
                a, b = items[i], items[j]
                if a[0] == b[0]:
                    continue
                hit = self._intersect(a, b)
                if hit:
                    problems.append(
                        f"文字叠加 {hit[0]}x{hit[1]}px：[{a[5][:26]}] {a[1:5]} <-> [{b[5][:26]}] {b[1:5]}")
        return problems

    # ---------------------------------------------------------------- 基础图元
    def rrect(self, x, y, w, h, fill, outline, radius=10, width=2, dash=False):
        if not dash:
            self.d.rounded_rectangle([x, y, x + w, y + h], radius=radius, fill=fill,
                                     outline=outline, width=width)
            return
        step = 10
        for x0 in range(x + radius, x + w - radius, step * 2):
            self.d.line([x0, y, min(x0 + step, x + w - radius), y], fill=outline, width=width)
            self.d.line([x0, y + h, min(x0 + step, x + w - radius), y + h], fill=outline, width=width)
        for y0 in range(y + radius, y + h - radius, step * 2):
            self.d.line([x, y0, x, min(y0 + step, y + h - radius)], fill=outline, width=width)
            self.d.line([x + w, y0, x + w, min(y0 + step, y + h - radius)], fill=outline, width=width)
        for cx, cy, start in ((x, y, 180), (x + w - 2 * radius, y, 270),
                              (x + w - 2 * radius, y + h - 2 * radius, 0),
                              (x, y + h - 2 * radius, 90)):
            self.d.arc([cx, cy, cx + 2 * radius, cy + 2 * radius], start=start,
                       end=start + 90, fill=outline, width=width)

    def box(self, box: Box) -> Box:
        """画框并把标题与正文居中排布；同时把「文字超出框体」记进 self.overflow。"""
        fill, outline = KIND_FILL[box.kind]
        self.rrect(box.x, box.y, box.w, box.h, fill, outline, radius=box.radius, dash=box.dash)
        tf, bf = font(box.title_size), font(box.font_size)
        pad = 14
        avail = box.w - 2 * pad
        t_lines = wrap(box.title, tf, avail) if box.title else []
        b_lines: list[str] = []
        for entry in box.body:
            b_lines.extend(wrap(entry, bf, avail))
        tgap, bgap = 5, 5
        gap_block = 9 if b_lines else 0
        total = text_h(t_lines, tf, tgap) + gap_block + text_h(b_lines, bf, bgap)
        # 上下各留 8px 的圆角余量；只有真正顶到边框才记为溢出
        if total > box.h - 16:
            self.overflow.append(f"[{box.title[:16]}] 文字高度 {total} > 框内容区 {box.h - 16}")
        cursor = box.y + max(6, (box.h - total) // 2)
        group = self._next_group()
        anchor = "la" if box.align == "left" else "ma"
        for line in t_lines:
            self._draw_text(group, (box.x + pad if box.align == "left" else box.cx, cursor),
                            line, tf, INK, anchor)
            cursor += tf.size + tgap
        cursor += gap_block
        for line in b_lines:
            self._draw_text(group, (box.x + pad if box.align == "left" else box.cx, cursor),
                            line, bf, SUB, anchor)
            cursor += bf.size + bgap
        for line in t_lines:
            if tf.getlength(line) > avail + 2:
                self.overflow.append(f"[{box.title[:16]}] 标题过宽：{line[:18]}")
        for line in b_lines:
            if bf.getlength(line) > avail + 2:
                self.overflow.append(f"[{box.title[:16]}] 正文过宽：{line[:18]}")
        self.boxes.append(box)
        return box

    # ---------------------------------------------------------------- 箭头
    def _head(self, x0, y0, x1, y1, color, size=9):
        ang = math.atan2(y1 - y0, x1 - x0)
        for sign in (1, -1):
            self.d.line([x1, y1,
                         x1 - size * math.cos(ang - sign * 0.42),
                         y1 - size * math.sin(ang - sign * 0.42)], fill=color, width=2)

    def _dashes(self, x0, y0, x1, y1, color, width, dash=9, gap=6):
        total = math.hypot(x1 - x0, y1 - y0)
        if total == 0:
            return
        ux, uy = (x1 - x0) / total, (y1 - y0) / total
        pos = 0.0
        while pos < total:
            end = min(pos + dash, total)
            self.d.line([x0 + ux * pos, y0 + uy * pos, x0 + ux * end, y0 + uy * end],
                        fill=color, width=width)
            pos = end + gap

    def arrow(self, p0, p1, color=EDGE, label=None, label_size=14, label_at=0.5,
              label_side: str = "right", dash=False, label_dy=0):
        """直线箭头。标签先铺底色再画线，保证标签压在线上而不是被线穿过。"""
        x0, y0 = p0
        x1, y1 = p1
        spot = None
        group = None
        if label:
            f = font(label_size)
            tw = f.getlength(label)
            lx = int(x0 + (x1 - x0) * label_at)
            ly = int(y0 + (y1 - y0) * label_at) + label_dy
            if label_side == "right":
                lx += 8
            elif label_side == "left":
                lx -= tw + 4
            else:
                lx -= int(tw / 2)
            spot = (lx, ly)
            group = self._next_group()
            self.d.rectangle([lx - 3, ly - 2, lx + tw + 3, ly + f.size + 3], fill=PAPER)
        if dash:
            self._dashes(x0, y0, x1, y1, color, 2)
        else:
            self.d.line([x0, y0, x1, y1], fill=color, width=2)
        self._head(x0, y0, x1, y1, color)
        if label and spot and group:
            self._draw_text(group, spot, label, font(label_size), color, "la")

    def poly_arrow(self, points: list[tuple[int, int]], color=EDGE, label=None, label_size=14,
                   dash=False, label_at=0):
        """折线箭头（用于指回上游的边）；label 贴在 label_at 指定的顶点左侧。"""
        for i in range(len(points) - 2):
            if dash:
                self._dashes(*points[i], *points[i + 1], color, 2)
            else:
                self.d.line([*points[i], *points[i + 1]], fill=color, width=2)
        self.arrow(points[-2], points[-1], color=color, dash=dash)
        if label:
            f = font(label_size)
            lx, ly = points[label_at]
            tw = f.getlength(label)
            self.d.rectangle([lx - tw - 36, ly - f.size // 2 - 3, lx - 30, ly + f.size // 2 + 3],
                             fill=PAPER)
            self._draw_text(self._next_group(), (lx - 32, ly), label, f, color, "rm")

    def note(self, x: int, y: int, text: str, color=None, size=17, max_px=None, gap=6) -> int:
        f = font(size)
        cursor = y
        lines = wrap(text, f, max_px or (self.w - x - M))
        group = self._next_group()
        for index, line in enumerate(lines):
            if cursor + f.size > self.h - 6:
                self.overflow.append(f"[注释] 超出画布底部：{text[:20]}")
                break
            self._draw_text(group, (x, cursor), line, f, color or SUB, "la")
            cursor += f.size + gap
            if index == len(lines) - 1:
                cursor -= gap
        return cursor

    def section(self, y: int, text: str) -> int:
        f = font(20)
        self._draw_text(self._next_group(), (M, y), text, f, INK, "la")
        self.d.line([M, y + 27, M + f.getlength(text) + 4, y + 27],
                    fill=(210, 216, 222), width=2)
        return y + 32

    def label(self, cx: int, cy: int, text: str, color=EDGE, size: int = 14, max_px: int = 150):
        """把一个短标签居中放在指定位置；文字会自动换行并铺白底。

        白底的作用是遮住标签下方的箭头线段，因此标签与「线」重叠是允许的；
        但标签与任何其它文字重叠都会被 text_collisions() 判为缺陷。
        """
        f = font(size)
        lines = wrap(text, f, max_px)
        w = max(f.getlength(line) for line in lines)
        h = text_h(lines, f, 3)
        left = int(cx - w / 2) - 4
        top = int(cy - h / 2) - 3
        self.d.rectangle([left, top, left + w + 8, top + h + 6], fill=PAPER)
        group = self._next_group()
        cursor = top + 3
        for line in lines:
            self._draw_text(group, (cx, cursor), line, f, color, "ma")
            cursor += f.size + 3

    def save(self, name: str) -> dict:
        problems = list(self.overflow) + self.text_collisions()
        for b in self.boxes:
            if b.x < 0 or b.y < 0 or b.right > self.w or b.bottom > self.h:
                problems.append(f"框越界：{b.title} @({b.x},{b.y},{b.w},{b.h})")
        path = os.path.join(OUT_DIR, name)
        self.img.save(path, "PNG", dpi=(DPI, DPI))
        size = os.path.getsize(path)
        pairs = len(self.texts) * (len(self.texts) - 1) // 2
        flag = "自检通过" if not problems else f"问题 {len(problems)} 处"
        print(f"[{'ok' if not problems else '!!'}] {name}  {self.w}x{self.h}px  {size} bytes  {flag}"
              f"  （文字块 {len(self.texts)} 个，比较 {pairs} 对）")
        for p in problems:
            print(f"      - {p}")
        return {"file": name, "w": self.w, "h": self.h, "bytes": size, "problems": problems,
                "texts": len(self.texts), "pairs": pairs}


# --------------------------------------------------------------------------- 通用布局

def make_box(col: int, y: int, title: str, body: list[str], kind: str = "plain",
             height: int = BOX_H) -> Box:
    return Box(COL[col], y, W, height, title, list(body), kind=kind)


def row(cv: Canvas, y: int, items: list[tuple[str, list[str], str]],
        height: int = BOX_H) -> list[Box]:
    boxes = [make_box(i, y, t, b, k, height) for i, (t, b, k) in enumerate(items)]
    for b in boxes:
        cv.box(b)
    for i in range(len(boxes) - 1):
        cv.arrow((boxes[i].right + 2, boxes[i].cy), (boxes[i + 1].x - 2, boxes[i + 1].cy))
    return boxes


def down(cv: Canvas, a: Box, b: Box, label: str | None = None, color=EDGE, dash=False,
         label_at=0.5, dx: int = 0):
    """纵向箭头 + 行间标签。

    标签不放在箭头旁边（那里宽度只有 20px，会伸进相邻列的框体正文里，属于真实缺陷），
    而是放在上下两行之间的水平留白里，与箭头同一条中轴线上、文字居中；
    竖直方向覆盖整段空隙，几何上与任何框内文字都不可能相交。
    """
    tip = (a.cx + dx, a.bottom + 2)
    tail = (b.cx + dx, b.y - 2)
    if label and tail[1] - tip[1] >= TAG_SIZE + 4:
        cv.arrow(tip, tail, color=color, dash=dash)
        f = font(TAG_SIZE)
        mid = (tip[1] + tail[1]) // 2
        group = cv.text_group()
        cv.d.rectangle([tip[0] - 40, tip[1], tip[0] + 40, tail[1]], fill=PAPER)
        cv._draw_text(group, (tip[0], mid - f.size // 2), label, f, color, "ma")
    else:
        cv.arrow(tip, tail, color=color, dash=dash)


def chain(cv: Canvas, boxes: list[Box], labels: list[str | None] | None = None):
    for i in range(len(boxes) - 1):
        label = labels[i] if labels and i < len(labels) else None
        down(cv, boxes[i], boxes[i + 1], label)


# --------------------------------------------------------------------------- 图 01：架构与进程

def fig01() -> dict:
    cv = Canvas(1120, "01 · 架构与进程边界",
                "Electron 编排三进程；SQLite 唯一写入方是 Java；Python 只监听 loopback 且不直连数据库")

    shell = Box(M, 100, CANVAS_W - 2 * M, 380, "", [], kind="shell", dash=True)
    cv.rrect(shell.x, shell.y, shell.w, shell.h, KIND_FILL["shell"][0], KIND_FILL["shell"][1],
             radius=12, dash=True)
    cv.d.text((shell.x + 18, shell.y + 10), "桌面壳 · Electron（apps/desktop）", font=font(21),
              fill=INK, anchor="la")

    r = cv.box(Box(M + 24, 150, 340, 130, "渲染进程 Renderer",
                   ["Vue 3 + TS + Element Plus",
                    "vite build 产物或 Vite 开发服务器",
                    "contextIsolation=true / sandbox=true",
                    "nodeIntegration=false"], kind="shell", font_size=16, title_size=21))
    p = cv.box(Box(M + 24 + 340 + 40, 150, 340, 130, "preload 白名单",
                   ["contextBridge 只暴露固定函数",
                    "禁止暴露 ipcRenderer / Node 全局",
                    "端口与令牌经同步 IPC 取得，不落盘"], kind="shell", font_size=16, title_size=21))
    cv.arrow((r.right + 3, r.cy), (p.x - 3, p.cy), label="IPC", label_size=14, label_side="center")

    cv.box(Box(M + 24, 306, 710, 152, "主进程 Main · ServiceManager",
               ["按顺序拉起：Python（--endpoint-stdout 中继端点）→ 代写 python-endpoint.json → Java",
                "统一注入 CSP：connect-src 仅 127.0.0.1 / localhost；禁止任意导航与新窗口",
                "子进程日志 UTF-8 逐行中继（StringDecoder）；端点行含令牌，消费掉不进日志"],
               kind="shell", font_size=16, title_size=21))
    jf = cv.box(Box(shell.right - 24 - 250, 306, 250, 152, "运行时端点文件",
                    ["runtime/java-endpoint.json",
                     "  { port, baseUrl, token, pid }",
                     "runtime/python-endpoint.json",
                     "  { baseUrl, token, relayedBy }"],
                    kind="store", font_size=15, title_size=20))
    cv.arrow((p.cx, p.bottom + 3), (jf.cx - 60, jf.y - 3), dash=True, color=(150, 160, 170),
             label="代写", label_size=14, label_side="center")

    y2 = 540
    j = cv.box(make_box(0, y2, "Java 业务服务 · Spring Boot 3",
                        ["只绑定 127.0.0.1，端口 0 由系统分配",
                         "业务规则权威；SQLite 唯一写入方",
                         "调用 Python 时带 X-Internal-Token"], "core"))
    py = cv.box(make_box(1, y2, "Python AI 服务 · FastAPI",
                         ["只监听 loopback，端口 0 由系统分配",
                          "模型适配 / 提示词 / 解析 / 引用过滤",
                          "不直连数据库，原文与分块由 Java 传入"], "ai"))
    sq = cv.box(make_box(2, y2, "SQLite（WAL + 外键）",
                         ["app.db：23 张普通表 + 1 张 FTS5 虚拟表",
                          "写入只发生在 Java 侧（含迁移与备份）",
                          "渲染层与 Python 都没有数据库句柄"], "store"))

    # 列间距只有 20px，放不下任何中文标签（实测 1px 也会压字），因此跨列关系不用自由标签表达，
    # 而是写进对应框的正文与下方注释里——自检会保证没有文字相交。
    cv.arrow((j.right + 3, j.cy), (py.x - 3, py.cy))
    cv.arrow((py.right + 3, py.cy), (sq.x - 3, sq.cy), dash=True, color=(150, 160, 170))

    y3 = 790
    data = cv.box(Box(COL[0], y3, W * 2 + GAP, 158, "本机数据目录（Electron userData）",
                      ["files/imports、files/exports（同一 sha256 只留一份）",
                       "backups（VACUUM INTO 备份与恢复前快照）",
                       "logs（java-api / python-ai）  runtime（端点文件、恢复请求）  index/fts"],
                      kind="store", font_size=16, title_size=21))
    ext = cv.box(Box(COL[2], y3, W, 158, "外部 LLM 端点",
                     ["默认 Ollama 127.0.0.1:11434/v1（qwen3:8b）",
                      "或任意 OpenAI 兼容端点",
                      "SSRF 校验：DNS 全量解析 + 私网/元数据拦截"],
                     kind="ext", font_size=16, title_size=21))
    cv.arrow((j.cx, j.bottom + 3), (data.x + 130, data.y - 3), color=(150, 160, 170),
             label="读写 files / backups / logs", label_size=14, label_at=0.32)
    cv.arrow((jf.cx, jf.bottom + 3), (data.x + data.w - 70, data.y - 3), dash=True,
             color=(150, 160, 170))
    cv.arrow((py.cx, py.bottom + 3), (ext.cx, ext.y - 3), color=(190, 90, 90),
             label="HTTPS / HTTP + apiKey", label_size=14, label_at=0.62)

    cv.note(M, 982,
            "边界说明：渲染层只能通过 preload 白名单访问主进程；业务数据只经 Java 落库；"
            "Python 由 Electron 拉起（ADR-002）并对 Java 呈现 HTTP 端点；AI 服务不可用时只降级 AI 能力，答题与判分不受影响。")
    cv.note(M, 1042,
            "令牌机制：Java 每次启动生成 256bit 随机 X-Session-Token，只在进程生命周期内有效；Python 用 X-Internal-Token 且只被 Java 调用。"
            "Java 把「端口 + 短期令牌」写入 runtime/java-endpoint.json，Python 端点同理；令牌不写日志、不进诊断输出。", color=WARN)
    return cv.save("01-架构与进程.png")


# --------------------------------------------------------------------------- 图 02：启动时序

def fig02() -> dict:
    cv = Canvas(1110, "02 · 启动时序",
                "目录 → 待恢复请求 → 迁移前备份 → 连接池 → 能力探测 → 迁移 V1/V2/V3 → Python → Java → /health → 窗口")

    r1 = row(cv, 106, [
        ("1 目录准备", ["createDirectories()：data / backups / logs / runtime",
                    "/ temp / files/imports / index/fts"], "plain"),
        ("2 待恢复请求", ["applyPendingRestore()：备份文件必须在 backups 内，",
                      "integrity_check 通过才替换"], "plain"),
        ("3 迁移前备份", ["仅「库已存在且脚本版本 > 当前 schema」时做；",
                      "VACUUM INTO，失败即中止启动（fail-fast）"], "plain"),
    ])
    r2 = row(cv, 106 + ROW_PITCH, [
        ("4 建连接池", ["HikariCP maximumPoolSize=8；",
                    "URL 带 foreign_keys=on / WAL / busy_timeout=5000"], "plain"),
        ("5 能力探测", ["池外先探一次用于「迁移前备份」判断；",
                    "池内再复探 fts5 与 fts5-trigram"], "plain"),
        ("6 执行迁移", ["V1__init → V2__resource_library → V3__documents_fts",
                    "每个脚本一个事务，失败整体回滚"], "plain"),
    ])
    r3 = row(cv, 106 + 2 * ROW_PITCH, [
        ("7 启动 Python", ["--port 0 --endpoint-file --endpoint-stdout；",
                       "30s 内等端点（端点文件或 stdout 中继先到即用）"], "ai"),
        ("8 中继端点", ["解析 AIA_ENDPOINT 行，原子代写 python-endpoint.json；",
                     "该行含令牌，绝不进日志"], "ai"),
        ("9 启动 Java", ["--aia.python-endpoint-file=… --server.port=0；",
                      "90s 内等 java-endpoint.json"], "core"),
    ])
    r4 = row(cv, 106 + 3 * ROW_PITCH, [
        ("10 /health 探活", ["GET /health（不鉴权）；30s 内通过则 up，",
                          "否则 down 并附最近 6 行子进程日志"], "core"),
        ("11 写运行时端点", ["Java 写 java-endpoint.json（端口 + 令牌 + pid）；",
                        "preload 同步 IPC 交给渲染层"], "core"),
        ("12 窗口加载", ["开发服务器可达则加载它，否则加载 dist/index.html；",
                      "都没有则显示「前端尚未构建」"], "shell"),
    ])
    chain(cv, r1 + r2 + r3 + r4,
          [None, None, "建池", "探测", "迁移",
           "Python 就绪", "端点就绪", "Java 就绪", "health 通过", "端点已写"])

    fb = cv.box(Box(COL[0], 826, W * 2 + GAP, BOX_H, "失败分支（不阻塞主流程）",
                    ["① Python 启动超时 → 状态 down，答题/判分/错题闭环照常（Java 读不到端点即降级 AI 能力）",
                     "② 端点出现后后台复查：每 3s 探一次 /health，窗口 120s（2 分钟），恢复即置 up",
                     "③ Java 未就绪 → 界面提示「本机服务未就绪」，可在系统设置页重试或重启服务"],
                    kind="plain", font_size=16, title_size=20))
    cv.poly_arrow([(40, 700), (40, fb.y + 40), (fb.x - 2, fb.y + 40)], color=WARN, dash=True)
    cv.label(74, 766, "超时 / 未就绪", color=WARN, max_px=74)

    cv.note(M, 986,
            "能力化迁移：V3 头部声明 -- @requires fts5-trigram；能力不满足时该脚本被显式跳过且不写入 schema_version，其余脚本照常执行，"
            "环境具备能力后下次启动自动补上；执行错误仍然一律回滚并返回 DB_MIGRATION_FAILED。")
    cv.note(M, 1046,
            "顺序约束：迁移必须在任何请求被处理前完成，因此发生在 dataSource @Bean 方法体内；迁移前备份必须早于连接池建立（ADR-006）。")
    return cv.save("02-启动时序.png")


# --------------------------------------------------------------------------- 图 03：业务主流程

def fig03() -> dict:
    cv = Canvas(930, "03 · 业务主流程",
                "建题 → 组卷 → 发布快照 → 作答 → 提交（先持久化再判分）→ 客观/主观双路径 → 错题闭环 → 再练习")

    r1 = row(cv, 106, [
        ("1 建题 / 改题", ["写 question + question_version（version_no 自增）",
                       "question_option.is_correct 由 answer.value 推导（ADR-003）"], "core"),
        ("2 组卷", ["paper_question 只引用已发布版本 + 题序",
                  "乐观锁冲突返回 VERSION_CONFLICT(409)"], "core"),
        ("3 发布", ["写不可变 paper_snapshot + 快照题目 snapshot_json",
                  "试卷转 published，之后题目改版不影响历史"], "core"),
    ])
    r2 = row(cv, 106 + ROW_PITCH, [
        ("4 作答", ["attempt(status=in_progress) + attempt_answer",
                  "逐题自动保存（PUT /attempts/{id}/answers/{sid}）"], "plain"),
        ("5 提交", ["事务内先持久化全部作答，再进入判分",
                  "Idempotency-Key：同键同载荷返回首次结果"], "plain"),
        ("6 客观题判分", ["单选/多选/判断/填空 → exact / normalized / partial_credit",
                      "写 grading_detail_json（ruleVersion 1.0 + expected/actual）"], "core"),
    ])
    r3 = row(cv, 106 + 2 * ROW_PITCH, [
        ("7 主观题待复核", ["grading_status=pending_review、score=null",
                        "入队 ai_grade 任务（幂等键 ai_grade:{answerId}）"], "ai"),
        ("8 AI 建议分", ["任务轮询 → 调 Python /grade-subjective",
                      "写 score + ai_rubric，状态仍是 pending_review"], "ai"),
        ("9 人工确认", ["POST /attempts/{id}/answers/{sid}/grade 确认或改分",
                     "只有人工动作后才转 graded，规则见 ADR-009"], "core"),
    ])
    r4 = row(cv, 106 + 3 * ROW_PITCH, [
        ("10 错题去重", ["按 question_version_id 唯一：答错 wrong_count+1、连续答对重置",
                      "同一题只保留一条错题记录"], "core"),
        ("11 错题状态机", ["active → reviewing → mastered；archived 可人工归档",
                       "wrong_question_review 驱动复习调度 next_review_at"], "core"),
        ("12 错题再练习", ["POST /wrong-questions/practice 生成 practice 快照",
                       "判分链路与试卷完全同构（ADR-008）"], "core"),
    ])
    chain(cv, r1 + r2 + r3 + r4,
          ["发布", "快照", "作答", "已保存", "出分",
           "待复核", "建议分", "人工确认", "计入错题"])
    cv.arrow((r3[2].cx, r3[2].bottom + 2), (r4[2].cx, r4[2].y - 2), dash=True, color=(150, 160, 170))
    cv.label(r4[2].cx - 250, r4[2].y - 14, "低于满分即计入错题", color=(120, 130, 140), max_px=100)
    cv.label(455, r4[0].y + 40, "计入错题", color=(120, 130, 140), max_px=76)

    cv.note(M, 756,
            "结果页数据来自 attempt + attempt_answer 聚合（GET /attempts/{id}/result）；未提交前接口不返回答案（防泄露），提交后再由判分链路写分。")
    cv.note(M, 816,
            "AI 相关步骤全部可缺省：无默认 LLM 档案时 ai_grade 以 CREDENTIAL_MISSING 判定为永久错误，主观题停留在 pending_review 等待人工评分，不影响其它题型。",
            color=WARN)
    return cv.save("03-业务主流程.png")


# --------------------------------------------------------------------------- 图 04：检索与引用

def fig04() -> dict:
    cv = Canvas(1190, "04 · 检索与引用",
                "导入补偿一致性 → FTS5(trigram) 检索与关键词降级 → 命中回溯分块 → 不可信输入 → 引用双重过滤")

    cv.section(96, "导入流水线（POST /api/v1/documents/import）")
    a = row(cv, 136, [
        ("A 暂存 + 校验", ["先写 <userData>/temp/；后缀白名单 .txt/.md/.markdown",
                       "重算 SHA-256 与入参比对（防 TOCTOU）"], "plain"),
        ("B 原子落盘", ["移动到 files/imports/<fileId>/<原始文件名>",
                     "路径不得逃逸数据目录；同 sha256 只留一份"], "plain"),
        ("C 短事务登记", ["登记 imported_file + document + document_chunk",
                      "DB 失败即补偿删除已落盘文件"], "plain"),
    ])
    d = row(cv, 136 + ROW_PITCH, [
        ("D 解码 + 分块", ["短事务登记成功后解码：UTF-8 → GB18030",
                       "装箱约 800 字符/块、上限 1200",
                       "超长按句边界硬切，不做重叠"], "core"),
        ("E 建索引", ["document_fts 只存可检索文本与 chunk_id",
                    "原文在 document_chunk.content；activeVersion 才对外服务"], "core"),
        ("F 一致性巡检", ["保留窗口 24h：清理孤儿文件与临时文件",
                      "「有记录没文件」→ 资料标记 failed 并保留详情"], "store"),
    ])
    # A→D 的绿色箭头表示「登记成功才进入解码分块」，这一步的语义写在 D 框正文里：
    # 列间距与行间距都放不下中文自由标签（会把字压到框内正文上），因此不再额外加标签。
    cv.arrow((a[2].cx, a[2].bottom + 2), (d[0].cx, d[0].y - 2), dash=True, color=(150, 160, 170))
    cv.arrow((a[0].cx, a[0].bottom + 2), (d[0].cx, d[0].y - 2), color=OK)

    cv.section(452, "检索与引用（GET /api/v1/search → POST /api/v1/ai/questions/{id}/explain）")
    g = row(cv, 492, [
        ("G 查询判定", ["q 1-200 字符；按空白切词加引号短语匹配",
                    "只读取 activeVersion 的分块"], "plain"),
        ("H FTS5(trigram)", ["bm25() 越小越相关 → score = -bm25，matchMode=fts5",
                         "中文按三字符组子串匹配，无需外部分词器"], "core"),
        ("I 关键词降级", ["触发：查询有效字符不足 3 / FTS5 命中为空",
                      "LIKE 检索，matchMode=keyword"], "ai"),
    ])
    j = row(cv, 650, [
        ("J 命中回溯分块", ["SearchHit = documentId / chunkId / pageNo / snippet",
                       "snippet 命中用 [[ ]] 标记，必须按纯文本渲染"], "core"),
        ("K 交给 Python", ["references = {refId:K1, title, pageNo, content}",
                       "content 属不可信输入：包裹前转义边界词"], "ai"),
        ("L 模型调用", ["只按本次实际提供的 refId 集合召回",
                     "返回 citations + injectionSuspected"], "ai"),
    ])
    m = row(cv, 808, [
        ("M 引用过滤（一次）", ["Python 按本次提供的 refId 过滤，臆造引用直接丢弃"], "ai"),
        ("N 引用复核（二次）", ["Java 再按 chunkId 是否真实存在复核后回填",
                          "对外 snippet 截断 200 字符"], "core"),
        ("O 返回引用来源", ["citations 必须能回到 GET /documents/{id}/chunks 逐字核对",
                       "matchMode 一并返回，界面标注"], "core"),
    ])
    chain(cv, g + j + m)
    cv.arrow((g[2].cx, g[2].bottom + 2), (j[0].cx, j[0].y - 2), dash=True, color=(150, 160, 170))

    drop = cv.box(Box(COL[0], 966, W * 2 + GAP, BOX_H,
                      "被丢弃的分支：模型给出的 refId / chunkId 不在本次提供集合内",
                      ["Python 过滤（一次）与 Java 按 chunkId 复核（二次）都不通过时，该引用不进入 citations",
                       "只保留「模型确实引用过 + 能落到真实分块」的引用；注入可疑只打标记与脱敏日志，不阻断主流程"],
                      kind="ext", font_size=16, title_size=19))
    cv.arrow((m[1].cx, m[1].bottom + 2), (drop.x + drop.w // 2, drop.y - 2), dash=True, color=WARN,
             label="引用不在集合内", label_size=14)

    cv.note(M, 1130,
            "降级点小结：① 中文短查询（<3 字符）无法走 trigram → 降级关键词路径并在界面标注 mode；"
            "② 索引重建失败保留旧版本，检索仍可用；③ Python 不可用时 explain 不可用，但导入、检索与分块查看仍由 Java 提供。")
    return cv.save("04-检索与引用.png")


# --------------------------------------------------------------------------- 图 05：一致性与恢复

def fig05() -> dict:
    cv = Canvas(1000, "05 · 一致性与恢复",
                "备份/恢复（登记请求 + 下次启动执行）、文件一致性巡检、索引版本化重建、迁移脚本防篡改")

    r1 = row(cv, 106, [
        ("1 备份", ["自动/手动都用 SQLite VACUUM INTO（WAL 下同样一致性）",
                  "产出 backups/app-<UTC 时间戳>-pre-migration.db"], "core"),
        ("2 恢复校验", ["POST /system/restore 只校验：integrity_check +",
                    "foreign_key_check + schema_version 存在"], "core"),
        ("3 登记恢复请求", ["写 runtime/restore-request.json，返回 requiresRestart=true",
                       "此时不动数据库文件（连接池正持有句柄）"], "plain"),
    ])
    r2 = row(cv, 106 + ROW_PITCH, [
        ("4 重启后替换", ["连接池建立之前：文件名必须在 backups 内且文件存在",
                      "替换前先 VACUUM INTO 当前库为 pre-restore 快照"], "core"),
        ("5 替换与清理", ["删除 app.db / -wal / -shm 后拷入备份，删除请求文件",
                      "失败保留现有数据库并删除请求，绝不做半恢复"], "core"),
        ("6 迁移脚本防篡改", ["schema_version 记录每个脚本 SHA-256",
                        "校验和不一致 → 拒绝启动，不覆盖用户数据"], "ext"),
    ])
    r3 = row(cv, 106 + 2 * ROW_PITCH, [
        ("7 文件巡检", ["GET /system/files：orphanFiles / missingFiles /",
                    "tempFiles / retentionHours（保留窗口 24h）"], "store"),
        ("8 补偿清理", ["POST /system/files/cleanup（dryRun 默认 true）",
                    "删超窗孤儿与临时文件；有记录没文件 → 标记 failed"], "store"),
        ("9 索引重建", ["POST /documents/reindex：建新 indexVersion → 全量重分块",
                    "→ 检索自检 → 通过才改 active_version"], "core"),
    ])
    r4 = row(cv, 106 + 3 * ROW_PITCH, [
        ("10 重建失败保留旧版", ["失败 → 返回 DOCUMENT_INDEX_FAILED，不改 active_version",
                           "旧版本在此之前一直对外服务"], "core"),
        ("11 排序可复现", ["两套排序都以 document_id 与 chunk_index 兜底",
                       "同一查询结果稳定可复现（可核对）"], "plain"),
        ("12 运行期红线", ["备份/恢复、迁移失败一律保留数据并报错",
                       "迁移前备份失败即中止启动（fail-fast）"], "ext"),
    ])
    chain(cv, r1 + r2 + r3 + r4,
          ["校验通过", "登记请求", "重启后执行", "替换完成", "脚本可信",
           "巡检/清理", "索引可用", "失败保留旧版", "结果可复现"])
    cv.arrow((r1[2].right - 40, r1[2].bottom + 2), (r2[0].x + 40, r2[0].y - 2), dash=True,
             color=(150, 160, 170))

    cv.note(M, 740,
            "取数口径：备份与恢复共用同一套 VACUUM INTO 实现；恢复请求只在下次启动、连接池建立之前执行（ADR-005），"
            "因此替换数据库文件不会让连接指向错误的 inode。")
    cv.note(M, 800,
            "已知限制：Windows 上关闭窗口时子进程被直接终止（非优雅停机），一致性由 SQLite WAL 恢复保证（README §8 第 5 条）。",
            color=WARN)
    return cv.save("05-一致性与恢复.png")


# --------------------------------------------------------------------------- 图 06：异步任务状态机

def fig06() -> dict:
    cv = Canvas(1100, "06 · 异步任务状态机",
                "async_task 即队列：原子认领 + 租约/心跳 + 指数退避与抖动 + 重试上限 + 幂等键；当前唯一任务类型 ai_grade")

    y1 = 116
    queued = cv.box(Box(M + 30, y1, 260, 132, "queued",
                        ["入队即写 payload + 幂等键",
                         "幂等键 ai_grade:{answerId} 唯一",
                         "max_attempts 由入队方给定（3）"],
                        kind="plain", font_size=15, title_size=24))
    running = cv.box(Box(queued.right + 90, y1, 260, 132, "running",
                         ["条件 UPDATE 原子认领",
                          "租约 2 分钟，运行期刷 heartbeat_at",
                          "外部模型调用不持有数据库长事务"],
                         kind="core", font_size=15, title_size=24))
    succ = cv.box(Box(running.right + 90, y1, 260, 132, "succeeded",
                      ["写 result_json（建议分 + 依据）",
                       "写 audit_event：attempt.ai_graded",
                       "作答状态仍为 pending_review"],
                      kind="core", font_size=15, title_size=24))
    cv.arrow((queued.right + 3, queued.cy), (running.x - 3, running.cy),
             label="轮询认领（每 3s）", label_size=14, label_side="center")
    cv.arrow((running.right + 3, running.cy), (succ.x - 3, succ.cy),
             label="执行成功", label_size=14, label_side="center")

    y2 = 340
    retry = cv.box(Box(running.x, y2, 260, 132, "retry_wait",
                       ["指数退避 5s × 2^(n-1)，上限 5 分钟 + 抖动",
                        "写 last_error_code / next_run_at",
                        "attempt_count 随重试递增"],
                       kind="ai", font_size=15, title_size=24))
    cv.arrow((running.cx - 70, running.bottom + 3), (retry.cx - 70, retry.y - 3), color=AMBER,
             label="可重试错误", label_size=14)
    cv.arrow((retry.cx + 70, retry.y - 3), (running.cx + 70, running.bottom + 3), color=AMBER,
             label="next_run_at 到点", label_size=14)

    failed = cv.box(Box(succ.x, y2, 260, 132, "failed",
                        ["永久错误（如未配置默认档案）",
                         "或 attempt_count ≥ max_attempts",
                         "人工 retry 可重新入队"],
                        kind="ext", font_size=15, title_size=24))
    cv.arrow((retry.right + 3, retry.cy), (failed.x - 3, failed.cy), color=WARN)

    cancelled = cv.box(Box(M + 30, y2, 260, 132, "cancelled",
                           ["POST /tasks/{id}/cancel",
                            "queued / running / retry_wait 均可取消"],
                           kind="plain", font_size=15, title_size=24))
    cv.arrow((cancelled.cx + 100, cancelled.y - 3), (running.cx - 100, running.bottom + 3),
             dash=True, color=(120, 130, 140), label="running 取消", label_size=14)
    cv.poly_arrow([(queued.x - 20, queued.bottom + 3), (queued.x - 20, y2 + 100),
                   (cancelled.x - 3, y2 + 100)], color=(120, 130, 140), dash=True)
    cv.label(190, y2 + 126, "queued 取消", color=(120, 130, 140), size=13, max_px=88)

    lease = cv.box(Box(M, 516, W * 2 + GAP, BOX_H, "租约过期回收（进程崩溃或强杀后的自愈）",
                       ["启动时 recoverExpiredLeases()：租约到期仍为 running 的任务回到 queued，清空 lease_owner / lease_until，",
                        "attempt_count 保留，因此重试次数不会被无限放大；running 期间 2 分钟不刷心跳即视为崩溃。"],
                       kind="plain", font_size=16, title_size=20))
    cv.arrow((running.cx, running.bottom + 3), (lease.x + lease.w - 90, lease.y - 3), dash=True,
             color=WARN)
    cv.label(940, 288, "租约过期（2 分钟无心跳）", color=WARN, max_px=110)
    cv.poly_arrow([(lease.x + 320, lease.y - 3), (lease.x + 320, queued.bottom + 3)], color=WARN,
                  dash=True)
    cv.label(430, 528, "重新排队", color=WARN, max_px=70)

    cv.note(M, 680,
            "不变量：① 外部模型调用绝不持有数据库长事务；② 同一幂等键只会有一个任务，重复提交返回既有任务 id；"
            "③ retry 接口只对 failed / cancelled 开放，重试不改变幂等键。")
    cv.note(M, 740,
            "④ 未知 task_type 直接置 failed（UNSUPPORTED_TASK_TYPE），不会静默丢弃；"
            "⑤ 轮询 fixedDelay=3s、initialDelay=5s，单次只认领一个任务，避免并发写冲突；"
            "⑥ 已 succeeded 或 failed 的任务不可取消，返回 409。")
    return cv.save("06-异步任务状态机.png")


# --------------------------------------------------------------------------- 入口

def main() -> int:
    print(f"字体：{FONT_PATH}")
    print(f"画布宽度：{CANVAS_W} px @ {DPI} DPI = {CANVAS_W / DPI * 2.54:.2f} cm（A4 版心 15.5 cm）")
    results = [fig01(), fig02(), fig03(), fig04(), fig05(), fig06()]
    print("\n=== 汇总 ===")
    for r in results:
        state = "OK" if not r["problems"] else f"{len(r['problems'])} 处问题"
        print(f"{r['file']:<26} {r['w']}x{r['h']} px  {r['bytes']:>8} bytes"
              f"  {r['w'] / DPI * 2.54:.2f} cm 宽  {state}")
    bad = [r for r in results if r["problems"]]
    if bad:
        print("存在自检问题，见上。")
        return 1
    print("全部图自检通过（无框越界、无文字溢出框体、无跨组文字像素叠加、宽度均 <= 15.5 cm @200DPI）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
