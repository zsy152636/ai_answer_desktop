# -*- coding: utf-8 -*-
"""生成《功能状态与系统流程图》Word 文档（可重复运行）。

    python make_doc.py

事实来源（脚本内所有数字都来自这四类材料，未核实的一律标注「未核实」）：
  * README.md（§1 阶段表与状态、§2 目录结构、§5 验证与测试、§6 关键取舍、§7 安全基线、§8 已知限制）
  * docs/architecture/decisions.md（ADR-001 ~ ADR-013）
  * docs/api/api-contract.md（§0 通用约定、§11 资料库与检索）
  * services/java-api/.../db/migration/V1~V3、web/*Controller.java、services/python-ai/app/api/routes_*.py、
    apps/renderer/src/pages/**/*.vue、services/**/test/**（由 scripts/inspect_facts 口径人工核对后写入本脚本）

排版约束：A4，页边距上下 2.5 cm / 左右 2.75 cm（版心 15.5 cm），插图宽度固定 15.4 cm；
        中文同时设置 run.font.name 与 w:eastAsia，避免 Word 里中文回退成方框字体。
"""

from __future__ import annotations

import os

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

HERE = os.path.dirname(os.path.abspath(__file__))
DOCS_DIR = os.path.dirname(HERE)
OUT_PATH = os.path.join(DOCS_DIR, "功能状态与系统流程图.docx")

FONT = "微软雅黑"
CONTENT_W_CM = 15.5
IMAGE_W_CM = 15.4
ACCENT = RGBColor(0x1F, 0x3B, 0x63)
MUTED = RGBColor(0x5F, 0x68, 0x73)

FIGURES = [
    ("01-架构与进程.png", "图 1 · 架构与进程边界", 2.0),
    ("02-启动时序.png", "图 2 · 启动时序", 2.2),
    ("03-业务主流程.png", "图 3 · 业务主流程", 1.9),
    ("04-检索与引用.png", "图 4 · 检索与引用", 2.4),
    ("05-一致性与恢复.png", "图 5 · 一致性与恢复", 2.0),
    ("06-异步任务状态机.png", "图 6 · 异步任务状态机", 2.2),
]

# --------------------------------------------------------------------------- 样式工具


def set_run(run, size=10.5, bold=False, color=None, italic=False):
    run.font.name = FONT
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.italic = italic
    if color is not None:
        run.font.color.rgb = color
    rpr = run._element.get_or_add_rPr()
    fonts = rpr.find(qn("w:rFonts"))
    if fonts is None:
        fonts = OxmlElement("w:rFonts")
        rpr.append(fonts)
    fonts.set(qn("w:ascii"), FONT)
    fonts.set(qn("w:hAnsi"), FONT)
    fonts.set(qn("w:eastAsia"), FONT)
    fonts.set(qn("w:cs"), FONT)
    return run


def para(doc, text="", size=10.5, bold=False, color=None, align=None, space_after=4,
         space_before=0, indent=0.0, italic=False):
    p = doc.add_paragraph()
    if align is not None:
        p.alignment = align
    pf = p.paragraph_format
    pf.space_after = Pt(space_after)
    pf.space_before = Pt(space_before)
    pf.line_spacing = 1.35
    if indent:
        pf.left_indent = Cm(indent)
    if text:
        set_run(p.add_run(text), size=size, bold=bold, color=color, italic=italic)
    return p


def heading(doc, text, level=1):
    sizes = {1: 15, 2: 12.5, 3: 11}
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.space_before = Pt(12 if level == 1 else 8)
    pf.space_after = Pt(5)
    pf.keep_with_next = True
    set_run(p.add_run(text), size=sizes.get(level, 11), bold=True, color=ACCENT)
    return p


def bullet(doc, text, size=10.5):
    p = doc.add_paragraph()
    pf = p.paragraph_format
    pf.space_after = Pt(2)
    pf.line_spacing = 1.32
    pf.left_indent = Cm(0.6)
    pf.first_line_indent = Cm(-0.4)
    set_run(p.add_run("· " + text), size=size)
    return p


def set_cell(cell, text, size=9.5, bold=False, color=None, align=None):
    cell.text = ""
    p = cell.paragraphs[0]
    if align is not None:
        p.alignment = align
    pf = p.paragraph_format
    pf.space_before = Pt(1.5)
    pf.space_after = Pt(1.5)
    pf.line_spacing = 1.2
    set_run(p.add_run(text), size=size, bold=bold, color=color)


def table(doc, header, rows, widths_cm, size=9.5):
    t = doc.add_table(rows=1, cols=len(header))
    t.style = "Table Grid"
    t.alignment = WD_TABLE_ALIGNMENT.CENTER
    t.autofit = False
    # 关键：Word/LibreOffice 只有在 tblLayout=fixed 且 tblGrid 显式给出列宽时才严格按给定宽度排版，
    # 否则会按内容重新分配列宽，出现「关键实现」这类长列被挤成窄条、单元格里满是换行的问题。
    tbl_pr = t._tbl.tblPr
    layout = OxmlElement("w:tblLayout")
    layout.set(qn("w:type"), "fixed")
    tbl_pr.append(layout)
    grid = t._tbl.find(qn("w:tblGrid"))
    if grid is not None:
        for i, gc in enumerate(grid.findall(qn("w:gridCol"))):
            gc.set(qn("w:w"), str(int(widths_cm[i] * 567)))
    for i, name in enumerate(header):
        set_cell(t.rows[0].cells[i], name, size=size, bold=True, color=ACCENT)
    for row in rows:
        cells = t.add_row().cells
        for i, value in enumerate(row):
            set_cell(cells[i], str(value), size=size)
    for r in t.rows:
        for i, cell in enumerate(r.cells):
            cell.width = Cm(widths_cm[i])
    # 表头重复（跨页时仍有表头）
    tr_pr = t.rows[0]._tr.get_or_add_trPr()
    header_flag = OxmlElement("w:tblHeader")
    header_flag.set(qn("w:val"), "true")
    tr_pr.append(header_flag)
    para(doc, "", size=2, space_after=0)
    return t


def figure(doc, filename, caption, width_cm):
    path = os.path.join(HERE, filename)
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(6)
    p.paragraph_format.space_after = Pt(2)
    p.paragraph_format.keep_with_next = True
    p.add_run().add_picture(path, width=Cm(width_cm))
    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    cap.paragraph_format.space_after = Pt(8)
    cap.paragraph_format.keep_with_next = True
    set_run(cap.add_run(caption), size=9, bold=True, color=MUTED)


def add_page_number_footer(section):
    footer = section.footer
    p = footer.paragraphs[0]
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run()
    set_run(run, size=9, color=MUTED)
    fld = OxmlElement("w:fldSimple")
    fld.set(qn("w:instr"), "PAGE")
    run._element.addnext(fld)


# --------------------------------------------------------------------------- 文档

def build() -> Document:
    doc = Document()

    # 页面与正文默认字体
    sec = doc.sections[0]
    sec.page_width = Cm(21.0)
    sec.page_height = Cm(29.7)
    sec.top_margin = Cm(2.5)
    sec.bottom_margin = Cm(2.5)
    sec.left_margin = Cm(2.75)
    sec.right_margin = Cm(2.75)
    add_page_number_footer(sec)

    style = doc.styles["Normal"]
    style.font.name = FONT
    style.font.size = Pt(10.5)
    style.element.rPr.rFonts.set(qn("w:eastAsia"), FONT)
    style.element.rPr.rFonts.set(qn("w:ascii"), FONT)
    style.element.rPr.rFonts.set(qn("w:hAnsi"), FONT)

    # ---------------------------------------------------------------- 封面区
    t = doc.add_paragraph()
    t.alignment = WD_ALIGN_PARAGRAPH.CENTER
    t.paragraph_format.space_after = Pt(2)
    set_run(t.add_run("AI 智能答题系统（学习伙伴 Agent）"), size=20, bold=True, color=ACCENT)
    s = doc.add_paragraph()
    s.alignment = WD_ALIGN_PARAGRAPH.CENTER
    s.paragraph_format.space_after = Pt(14)
    set_run(s.add_run("功能状态与系统流程图 · 第一版"), size=13, bold=True)

    # ---------------------------------------------------------------- 1 文档说明
    heading(doc, "1. 文档说明", 1)
    para(doc, "用途：作为第一版交付的功能状态说明与系统流程图册，供评审、验收与后续阶段排期使用；"
              "功能清单与流程图一一对应，未实现的能力在 §4 明确标注，不以「部分可用」措辞掩盖。")
    para(doc, "编制依据：")
    bullet(doc, "《AI 智能答题系统开发实施文档 v1.0》的阶段划分与阶段表（阶段 0-8）")
    bullet(doc, "仓库实现：apps/desktop、apps/renderer、services/java-api、services/python-ai")
    bullet(doc, "docs/api/api-contract.md（v1 接口契约，冻结）与 contracts/openapi/openapi.yaml、contracts/json-schema/llm-config.schema.json")
    bullet(doc, "docs/architecture/decisions.md（ADR-001 ~ ADR-013）")
    bullet(doc, "README.md §1 阶段表、§5 验证与测试、§8 已知限制")
    para(doc, "状态口径：", bold=True, space_before=4)
    bullet(doc, "✅ 已完成：代码、接口与测试均已落地，且能用仓库内的脚本复跑验证。")
    bullet(doc, "🟡 部分受限：功能可用，但存在明确边界（例如凭据后端在非 Windows 平台降级、检索无向量路径）。")
    bullet(doc, "⛔ 未开发：第一版明确不做，接口层面通常返回明确的错误码或直接不提供入口。")
    para(doc, "数据截止时间：本文档按工作区当前代码状态编制（编制时工作区包含 services/java-api/target/answer-java-api.jar、"
              "apps/renderer/dist/index.html、services/python-ai/.venv 三处构建产物，均存在）。"
              "涉及外部环境的项（浏览器内人工点击验收、单次全量冒烟运行）在 §6 单独标注为「未执行」。", space_before=4)
    para(doc, "口径差异声明（不修改既有文件，仅在此记录）：README.md §2 目录结构里写「渲染层 13 个页面」，"
              "按 apps/renderer/src/pages/**/*.vue 实际清点为 14 个（见 §8.4）；"
              "README.md §2 也未列出 V1/V2/V3 迁移脚本，本文档按迁移脚本逐条清点得到「普通表 23 张 + FTS5 虚拟表 1 张、索引 23 个」（见 §8.3）。",
         color=MUTED)

    # ---------------------------------------------------------------- 2 系统概览
    heading(doc, "2. 系统概览", 1)

    heading(doc, "2.1 技术栈", 2)
    table(doc,
          ["层", "技术选型", "在系统中的职责"],
          [
              ["桌面壳", "Electron（主进程 + preload）", "子进程编排、窗口与安全基线、运行时会话下发"],
              ["渲染层", "Vue 3 + TypeScript + Vite + Element Plus + Pinia + vue-router", "14 个页面，全部业务交互"],
              ["业务服务", "Java 17 / Spring Boot 3 / HikariCP / sqlite-jdbc", "业务规则权威、SQLite 唯一写入方、迁移与备份"],
              ["AI 服务", "Python 3.12 / FastAPI / httpx", "模型适配、提示词、解析、引用过滤、注入检测标记"],
              ["数据库", "SQLite（WAL + 外键 + 版本化迁移）", "23 张普通表 + 1 张 FTS5 虚拟表、23 个索引"],
              ["契约", "OpenAPI + JSON Schema", "contracts/openapi/openapi.yaml、contracts/json-schema/llm-config.schema.json"],
          ],
          [2.2, 5.6, 7.7])

    heading(doc, "2.2 三进程职责与边界", 2)
    bullet(doc, "Electron 主进程：唯一负责拉起与回收子进程（ADR-002），先启 Python、再启 Java；"
                "窗口先显示「正在启动服务」，再 await services.startAll()。不实现任何业务规则。")
    bullet(doc, "渲染进程：contextIsolation=true、sandbox=true、nodeIntegration=false，只通过 preload 白名单与主进程通信；"
                "禁止任意导航与新窗口，注入 CSP（connect-src 只放行 127.0.0.1 / localhost）。")
    bullet(doc, "Java 业务服务：业务规则与数据一致性的唯一权威；SQLite 唯一写入方（迁移、备份、恢复前快照都在 Java 侧完成）。")
    bullet(doc, "Python AI 服务：只监听 loopback，不直连数据库；资料原文、分块与引用集合由 Java 通过 HTTP 传入。")
    bullet(doc, "降级边界：AI 服务不可用只影响 AI 讲解与主观题建议分，答题、判分、错题闭环照常工作。")

    heading(doc, "2.3 数据目录布局（本机）", 2)
    table(doc,
          ["目录", "内容", "写入方"],
          [
              ["app.db（data 根）", "SQLite 主库（WAL 模式，含 -wal / -shm）", "Java"],
              ["backups/", "VACUUM INTO 备份、迁移前备份 app-<UTC>-pre-migration.db、恢复前快照 app.db.pre-restore-*.db", "Java"],
              ["files/imports/、files/exports/", "导入的资料原文（同一 sha256 只留一份）与导出文件", "Java"],
              ["logs/", "java-api 与 python-ai 的子进程日志（主进程逐行中继，UTF-8）", "Electron 主进程"],
              ["runtime/", "java-endpoint.json、python-endpoint.json（端口 + 短期令牌）、restore-request.json", "Java / Electron 主进程"],
              ["temp/", "导入暂存文件（校验通过后原子移动到 files/imports）", "Java"],
              ["index/fts/", "本地检索索引版本目录", "Java"],
              ["secrets/", "DPAPI 加密后的凭据文件（*.dpapi，仅存放密文）", "Java"],
          ],
          [3.4, 8.6, 3.5])
    para(doc, "开发态数据目录为 %APPDATA%\\ai-answer-desktop（已核对存在，含 backups / files / index / logs / runtime / temp / secrets 等子目录）；"
              "打包态的路径由 Electron 的 userData 决定，可用 AIA_USER_DATA_DIR 覆盖。", size=10, color=MUTED)

    heading(doc, "2.4 端口与令牌机制", 2)
    bullet(doc, "随机端口：Java（--server.port=0）与 Python（--port 0）都由系统分配空闲端口，启动后把端口写入 runtime 下的端点文件；"
                "主进程启动前会先删除上一轮的端点文件，避免把陈旧端口当成服务就绪。")
    bullet(doc, "短期令牌：Java 每次启动生成 256bit 随机 X-Session-Token，只在本进程生命周期内有效（退出即失效）；"
                "业务接口除 /health 外一律要求该头，缺失或错误返回 401 UNAUTHORIZED。")
    bullet(doc, "Python 内部令牌：X-Internal-Token，由 Java 独占调用；令牌不写日志、不进诊断输出。")
    bullet(doc, "绑定与本机校验：两个服务都只绑定 127.0.0.1；Java 额外校验 Host 与 Origin（不合法返回 403 ORIGIN_REJECTED）。")
    bullet(doc, "端点交付双路径（ADR-012）：Python 直写端点文件，同时用 --endpoint-stdout 把端点以单行 JSON（前缀 AIA_ENDPOINT）"
                "写给父进程，父进程原子代写 python-endpoint.json；该行含令牌，被消费掉不入日志。")

    # ---------------------------------------------------------------- 3 已完成功能
    heading(doc, "3. 已完成功能清单", 1)
    para(doc, "下表按开发文档阶段 1-6 组织。每行的「验证证据」列给出可复跑的测试文件、断言口径或实测记录；"
              "标 🟡 的行表示功能可用但边界明确。总计：Java 34 项测试、Python 174 项测试、前端 vue-tsc exit 0 + vite build 成功、"
              "端到端冒烟阶段 1-5 共 79 项 + 阶段 6 隔离 24 项。", size=10)

    heading(doc, "3.1 阶段 1 · 工程骨架", 2)
    table(doc,
          ["模块", "功能点", "关键实现", "状态", "验证证据"],
          [
              ["进程编排", "Electron 统一拉起 Python 与 Java", "ServiceManager：先 Python（等端点 30s）再 Java（等端点 90s）",
               "✅", "README §1/§4；service-manager.ts"],
              ["安全基线", "preload 白名单 + 上下文隔离", "contextBridge 只暴露固定函数；contextIsolation/sandbox=true、nodeIntegration=false",
               "✅", "preload.ts；契约 §10"],
              ["安全基线", "CSP 与导航限制", "connect-src 仅 self/127.0.0.1/localhost；导航拦截、新窗口 deny",
               "✅", "main.ts"],
              ["一键启动", "dev-start / dev-stop / mvn 封装", "构建 Java → 启 Python → 启 Java（固定端口 18765）→ 写 .runtime/java-endpoint.json",
               "✅", "README §4.2；scripts/*.ps1"],
              ["可观测", "日志与端点中继", "StringDecoder 逐行解码；AIA_ENDPOINT 行由父进程代写端点文件（ADR-012）",
               "✅", "ADR-012；service-manager.ts"],
              ["端点", "健康检查", "GET /health（不鉴权）返回 status/service/version 与 provider 列表",
               "✅", "routes_health.py；HealthController"],
          ],
          [1.6, 2.7, 5.2, 1.1, 4.9], size=9)

    heading(doc, "3.2 阶段 2 · 数据基线", 2)
    table(doc,
          ["模块", "功能点", "关键实现", "状态", "验证证据"],
          [
              ["表结构", "全量 DDL 与索引", "V1：19 张普通表 + 17 索引；V2：4 张 + 6 索引；V3：document_fts 虚拟表",
               "✅", "MigrationRunnerTest（5 项）"],
              ["迁移执行器", "自研执行器 + SHA-256 校验和", "按版本排序、每脚本一个事务；已应用版本校验和不一致即拒绝启动（ADR-007）",
               "✅", "MigrationRunnerTest；ADR-007"],
              ["迁移前备份", "连接池建立前强制备份", "库已存在且脚本版本更高时 VACUUM INTO；失败即中止启动（ADR-006）",
               "✅", "DataSourceConfig；ADR-006"],
              ["备份/恢复", "VACUUM INTO 与完整性校验", "integrity_check + foreign_key_check + schema_version 存在",
               "✅", "ADR-005；DatabaseBootstrap"],
              ["恢复请求模式", "登记请求 + 下次启动执行", "POST /system/restore 只写 restore-request.json 并返回 requiresRestart=true",
               "✅", "§5 流程图；ADR-005"],
              ["文件一致性", "孤儿/缺失巡检与补偿", "保留窗口 24h；GET /system/files 报告、cleanup 清理并把缺文件资料标记 failed",
               "✅", "契约 §11.5；DocumentFlowIntegrationTest"],
              ["题目版本", "题目版本化与选项规范化", "question_version 版本自增；is_correct 由 answer.value 推导（ADR-003）",
               "✅", "ApiFlowIntegrationTest；ADR-003"],
              ["试卷快照", "发布即快照", "paper_snapshot + snapshot_json，判分只读快照",
               "✅", "ApiFlowIntegrationTest（4 项）"],
          ],
          [1.6, 2.7, 5.2, 1.1, 4.9], size=9)

    heading(doc, "3.3 阶段 3 · 核心答题", 2)
    table(doc,
          ["模块", "功能点", "关键实现", "状态", "验证证据"],
          [
              ["题库", "题目 CRUD", "GET/POST /questions、GET/PUT/DELETE /questions/{id}、publish/archive/versions",
               "✅", "QuestionController（8 端点）"],
              ["题库", "乐观锁与版本冲突", "并发修改返回 VERSION_CONFLICT（409）",
               "✅", "ErrorCode.VERSION_CONFLICT"],
              ["试卷", "组卷与发布快照", "paper_question 引用已发布版本；POST /papers/{id}/publish 写不可变快照",
               "✅", "PaperController（8 端点）"],
              ["作答", "作答自动保存", "PUT /attempts/{id}/answers/{snapshotQuestionId} 逐题保存",
               "✅", "AttemptController（9 端点）"],
              ["判分", "确定性判分与判分明细", "exact / normalized / partial_credit；grading_detail_json 写 ruleVersion=1.0（ADR-011）",
               "✅", "GradingServiceTest（14 项）"],
              ["提交", "幂等提交", "同键同载荷返回首次结果，不同载荷返回 IDEMPOTENCY_CONFLICT；记录保留 7 天",
               "✅", "IdempotencyFilter；冒烟"],
              ["结果页", "结果聚合与答案保护", "GET /attempts/{id}/result；未提交前接口不返回答案",
               "✅", "冒烟「未提交不泄露答案」"],
              ["作答记录", "列表与放弃", "GET /attempts（分页）、POST /attempts/{id}/abandon",
               "✅", "AttemptController"],
          ],
          [1.6, 2.7, 5.2, 1.1, 4.9], size=9)

    heading(doc, "3.4 阶段 4 · 错题闭环", 2)
    table(doc,
          ["模块", "功能点", "关键实现", "状态", "验证证据"],
          [
              ["错题去重", "按题目版本唯一", "wrong_question UNIQUE(question_version_id)；答错 wrong_count+1",
               "✅", "V1__init.sql；ApiFlowIntegrationTest"],
              ["状态机", "active → reviewing → mastered", "CHECK 限定四态；archived 可人工归档；PATCH /wrong-questions/{id}",
               "✅", "契约 §5；WrongQuestionController（5 端点）"],
              ["复习调度", "复习记录驱动 next_review_at", "wrong_question_review 记录间隔与结果；POST /{id}/review",
               "✅", "契约 §5；冒烟「错题闭环」"],
              ["再练习", "错题生成练习快照", "paper_snapshot(kind='practice', paper_id=NULL)，判分链路同构（ADR-008）",
               "✅", "ADR-008；ApiFlowIntegrationTest"],
          ],
          [1.6, 2.7, 5.2, 1.1, 4.9], size=9)

    heading(doc, "3.5 阶段 5 · LLM 配置", 2)
    table(doc,
          ["模块", "功能点", "关键实现", "状态", "验证证据"],
          [
              ["多档案", "profile CRUD 与默认档案", "llm-profiles 增删改查、PUT /default；provider 限定 4 种",
               "✅", "LlmProfileController（12 端点）"],
              ["校验", "JSON Schema 单一来源", "networknt 加载 contracts/json-schema/llm-config.schema.json",
               "✅", "ADR-010；冒烟「Schema 校验」"],
              ["校验", "跨字段规则后端补齐", "defaultProfileId 必须存在、profiles[].id 唯一，在后端补齐",
               "✅", "ADR-010"],
              ["导入", "预览/确认分离", "POST /import/preview 落 llm_import_preview（带 expires_at）→ /import/confirm",
               "✅", "冒烟「导入预览-确认分离」"],
              ["导出", "脱敏导出", "POST /export 永不含明文密钥，只导出 credential:// 引用",
               "✅", "test_redaction.py（13 项）"],
              ["凭据", "DPAPI 加密存储", "DPAPI（当前用户作用域）写 secrets/*.dpapi；探测失败才降级并标注（ADR-004）",
               "🟡", "ADR-004；非 Windows 降级"],
              ["连通性", "测试连接与错误分类", "POST /test-connection 恒 200 返回 ok/category，按 DNS/TLS/鉴权/模型/额度/超时分类",
               "✅", "test_connection.py（16 项）"],
              ["出口安全", "SSRF 拦截", "协议/凭据/端口校验 + DNS 全量解析 + 私网与元数据拦截 + 逐跳重定向复检",
               "✅", "test_url_guard.py（18 项）"],
              ["主观题", "AI 建议分 + 人工确认", "AI 写建议分后仍 pending_review，人工确认才转 graded（ADR-009）",
               "✅", "ADR-009；TaskService"],
              ["讲解", "题目讲解", "POST /ai/questions/{id}/explain 调 Python /explain，返回讲解与引用",
               "✅", "test_explain.py（12 项）"],
          ],
          [1.6, 2.7, 5.2, 1.1, 4.9], size=9)

    heading(doc, "3.6 阶段 6 · 本地检索 / AI", 2)
    table(doc,
          ["模块", "功能点", "关键实现", "状态", "验证证据"],
          [
              ["资料导入", "纯文本导入与 SHA-256 去重", "白名单 .txt/.md/.markdown；服务端重算 SHA-256 防 TOCTOU；同 sha256 只留一份",
               "✅", "DocumentFlowIntegrationTest（5 项）"],
              ["资料导入", "原子移动 + 短事务 + 补偿", "temp → 原子移动 files/imports/<fileId>/<原名> → 短事务登记；失败删文件",
               "✅", "契约 §11.5；冒烟 24 项"],
              ["资料导入", "重复导入三分支", "reject（默认 409）→ ?sha256= 反查 → 复用 / onDuplicate=new / 取消",
               "✅", "契约 §11.2；冒烟"],
              ["分块", "确定性分块", "按空行分段装箱约 800 字符/块、上限 1200，按句边界硬切，不重叠",
               "✅", "TextChunkerTest（6 项）；ADR-013"],
              ["编码", "UTF-8 → GB18030 解码", "两者都不成立才报 DOCUMENT_PARSE_FAILED；记录 parser_version",
               "✅", "ADR-013；冒烟「非文本 400」"],
              ["检索", "FTS5(trigram) 检索", "document_fts 只存文本与 chunk_id；逐词加引号 + AND；score = -bm25",
               "✅", "DocumentFlowIntegrationTest；冒烟"],
              ["检索", "关键词降级路径", "有效字符 < 3 / FTS5 不可用 / 命中为空 → LIKE 检索，界面标注 mode",
               "✅", "冒烟「短词降级」；ADR-013"],
              ["检索", "能力化迁移", "V3 的 -- @requires fts5-trigram 不满足则跳过且不记入 schema_version",
               "✅", "冒烟；MigrationRunnerTest"],
              ["索引", "版本化重建与切换", "新版本 → 全量重分块 → 检索自检 → 通过才切 active_version；失败保留旧版",
               "✅", "冒烟；IndexStateService"],
              ["引用", "引用式 AI 解释与回溯", "citations 双重过滤（Python 按 refId、Java 按 chunkId）；snippet 截断 200 字符",
               "✅", "test_explain_references.py（28 项）"],
              ["安全", "提示注入防护", "包裹前转义边界词 + 固定边界标签 + 可疑模式只打标记与脱敏日志",
               "✅", "ADR-013；test_explain_references.py"],
              ["页面", "资料库页面", "资料列表 / 导入 / 分块预览 / 检索 / 索引状态 / 文件一致性",
               "✅", "DocumentsPage.vue（/documents）"],
          ],
          [1.6, 2.7, 5.2, 1.1, 4.9], size=9)

    # ---------------------------------------------------------------- 4 未开发
    heading(doc, "4. 未开发 / 受限功能清单", 1)
    para(doc, "「未开发」不等于「坏了」：下表中的条目要么在契约里返回明确错误码，要么根本没有入口。", size=10)

    heading(doc, "4.1 阶段 7 · 试卷导入（第一版不做）", 2)
    table(doc,
          ["功能项", "现状", "计划阶段", "影响与替代方案"],
          [
              ["PDF / DOCX 文本提取", "⛔ 未开发：超范围格式返回 DOCUMENT_FORMAT_UNSUPPORTED 并在界面提示，不做半成品解析", "阶段 7",
               "替代：手工建题（题库页面），或先用外部工具转成 .txt/.md 再导入资料库"],
              ["OCR（扫描件识别）", "⛔ 未开发", "阶段 7", "替代：无；扫描件当前无法进入系统"],
              ["AI 候选题目与逐题校对", "⛔ 未开发", "阶段 7", "替代：无；题目只能人工录入"],
              ["试卷导入工作台", "⛔ 未开发", "阶段 7", "替代：无；试卷只能手工组卷"],
          ],
          [3.2, 5.6, 1.8, 4.9], size=9)

    heading(doc, "4.2 阶段 8 · 稳定发布（第一版不做）", 2)
    table(doc,
          ["功能项", "现状", "计划阶段", "影响与替代方案"],
          [
              ["打包与签名", "⛔ 未开发：Electron 目前以开发方式运行（pnpm start）", "阶段 8", "替代：开发态运行；README §4.3"],
              ["自动更新与回滚", "⛔ 未开发", "阶段 8", "替代：手动替换构建产物"],
              ["诊断包导出", "⛔ 未开发（无一键诊断包）", "阶段 8", "替代：手工收集 logs/ 与 runtime/ 端点文件（注意端点文件含令牌）"],
              ["运行时分发（jlink / PyInstaller）", "⛔ 未开发：当前依赖本机 JDK 17 与 services/python-ai/.venv", "阶段 8",
               "替代：按 README §3 准备环境；Python 由 Electron 拉起（ADR-002）"],
              ["渲染层自动化测试框架", "⛔ 未开发：仅 vue-tsc --noEmit + vite build，无表单校验与页面状态断言", "阶段 8",
               "替代：冒烟脚本覆盖接口层；页面状态依赖人工点击（见 §6 未执行项）"],
          ],
          [3.2, 5.6, 1.8, 4.9], size=9)

    heading(doc, "4.3 已知限制（README §8 明确标注）", 2)
    table(doc,
          ["限制", "说明", "影响", "状态"],
          [
              ["资料导入只支持纯文本", ".txt / .md / .markdown；PDF/DOCX/OCR 属阶段 7", "试卷类资料需先手工转换", "🟡 有意为之"],
              ["检索只有两条路径", "FTS5(trigram) 与关键词 LIKE；无向量索引、无重排", "召回质量依赖子串匹配；中文查询不足 3 字符必然降级", "🟡 已知"],
              ["冒烟脚本单次全量未跑完", "一次完整运行会长时间停在 [10] AI 讲解与主观题建议分（真实模型）段落", "已验证：阶段 1-5 共 79 项全量通过 + 阶段 6 共 24 项隔离运行全绿", "🟡 已知"],
              ["Windows 进程终止非优雅停机", "关闭窗口时子进程被直接终止（taskkill /T /F）", "一致性由 SQLite WAL 恢复保证，不做优雅停机", "🟡 已知"],
              ["Element Plus 全量引入", "前端 CSS 约 391 kB（gzip 53 kB）", "首屏体积偏大；按需引入留待后续优化", "🟡 已知"],
              ["非 Windows 平台凭据降级", "Keychain / libsecret 未接入，凭据退化为文件存储", "凭据保护强度下降，界面与日志显式标注后端名称", "🟡 已知"],
              ["Python 由 Electron 而非 Java 拉起", "ADR-002 的设计取舍（打包方案未冻结）", "接口不变，阶段 8 可迁回 Java", "🟡 已知"],
          ],
          [3.2, 5.6, 4.7, 2.0], size=9)

    # ---------------------------------------------------------------- 5 流程图
    heading(doc, "5. 系统流程图", 1)
    para(doc, "6 张图按「结构 → 启动 → 业务 → 检索 → 一致性 → 异步任务」排列；每张图后给出它回答的问题与关键分支/降级点。"
              "所有图的实际宽度为 15.4 cm（图片像素宽 1220 px @200 DPI ≈ 15.49 cm），不超过 15.5 cm 版心。", size=10)

    captions = {
        0: [
            "这张图回答什么问题：系统有哪些进程、谁负责什么、数据最终落到哪里、哪些通路是被显式禁止的。",
            "关键边界：SQLite 的唯一写入方是 Java；Python 只监听 loopback 且不直连数据库；渲染层只能经 preload 白名单访问主进程。",
            "关键机制：端点文件（java/python-endpoint.json）+ 每次启动随机生成的短期令牌 + 只绑定 127.0.0.1。",
            "降级点：AI 服务不可用时只降级 AI 能力（讲解、主观题建议分），答题与判分不受影响。",
        ],
        1: [
            "这张图回答什么问题：从点开应用到窗口可用，中间每一步的先后顺序与硬约束在哪里。",
            "顺序硬约束：目录准备 → 待恢复请求 → 迁移前备份 → 建连接池 → 能力探测 → 执行迁移 → 拉 Python → 拉 Java → /health → 加载窗口。",
            "关键分支：① 迁移前备份失败直接中止启动（fail-fast）；② V3 声明 -- @requires fts5-trigram，能力不满足则跳过且不记入 schema_version。",
            "降级点：Python 启动超时（30s）不阻塞主流程，端点出现后每 3s 复查 /health、窗口 120s（2 分钟），恢复即置 up。",
            "失败提示：Java 未就绪时界面显示「本机服务未就绪」，可在系统设置页重试或重启服务。",
        ],
        2: [
            "这张图回答什么问题：一道题从建题到进入错题本、再被练习，中间的数据形态如何变化。",
            "关键设计：发布即写不可变快照，判分只读快照 JSON；题目改版不影响历史成绩（ADR-003）。",
            "关键分支：提交时先持久化全部作答再判分；客观题立即确定性出分，主观题落 pending_review 并排队 ai_grade 任务。",
            "人工闸门：AI 只写建议分，状态仍是 pending_review，必须人工确认或改分才转 graded（ADR-009）。",
            "闭环：低于满分即计入错题（按题目版本去重）→ 状态机驱动复习调度 → 再练习生成 practice 快照，判分链路同构（ADR-008）。",
        ],
        3: [
            "这张图回答什么问题：资料怎么进来、怎么被切成可回溯的分块、检索命中后如何变成「有出处的」AI 回答。",
            "导入一致性：temp → SHA-256 校验 → 原子移动到 files/imports → 短事务登记；登记失败补偿删除文件；同一 sha256 只留一份。",
            "检索两条路径：FTS5(trigram) 优先（score=-bm25）；查询有效字符不足 3、FTS5 不可用或命中为空时降级为关键词 LIKE，并在界面标注 mode。",
            "不可信输入：检索片段包裹前先转义边界词，再用固定边界标签包裹，命中可疑指令模式只打标记（injectionSuspected）与脱敏日志。",
            "被丢弃的分支：模型给出的 refId / chunkId 不在本次提供集合内时，Python 过滤（一次）与 Java 按 chunkId 复核（二次）都会丢弃它。",
        ],
        4: [
            "这张图回答什么问题：数据在什么时机被备份、恢复为什么要重启、文件与索引不一致时系统怎么自愈。",
            "备份/恢复：VACUUM INTO 生成一致性快照；恢复只做校验与登记（requiresRestart=true），真正的替换发生在下次启动、连接池建立之前（ADR-005）。",
            "安全前置：替换前先把当前库另存为 app.db.pre-restore-<时间戳>.db；任何校验失败都放弃恢复并保留现有数据，不做半恢复。",
            "文件一致性：保留窗口 24h 清理孤儿与临时文件；「有记录没文件」把资料标记 failed 并保留详情，不发布部分解析结果。",
            "索引版本化：新版本构建并通过检索自检后才切换 active_version；失败返回 DOCUMENT_INDEX_FAILED 且旧版本继续服务。",
            "防篡改：schema_version 记录每个迁移脚本的 SHA-256，校验和不一致即拒绝启动。",
        ],
        5: [
            "这张图回答什么问题：异步任务在崩溃、重试、人工取消等情况下会走到哪个状态，以及为什么不会重复执行。",
            "主链：queued → running → succeeded；running 期间持有 2 分钟租约并刷心跳。",
            "重试：可重试错误 → retry_wait（指数退避 5s × 2^(n-1)，上限 5 分钟 + 抖动）→ 到点重新认领；重试上限用尽或永久错误 → failed。",
            "取消与回收：queued / running / retry_wait 均可取消（已结束的任务返回 409）；租约到期仍为 running 的任务在启动时回到 queued。",
            "幂等与隔离：幂等键唯一（ai_grade:{answerId}），重复提交返回既有任务 id；外部模型调用绝不持有数据库长事务。",
            "范围：当前唯一的任务类型是 ai_grade（主观题 AI 建议分）；未知 task_type 直接置 failed，不静默丢弃。",
        ],
    }

    for index, (filename, caption, ratio) in enumerate(FIGURES):
        heading(doc, f"5.{index + 1} {caption.split('· ')[1]}", 2)
        figure(doc, filename, caption, IMAGE_W_CM)
        width_cm = round(IMAGE_W_CM * ratio, 0)
        for line in captions[index]:
            bullet(doc, line, size=10)
        para(doc, "", size=4, space_after=2)

    # ---------------------------------------------------------------- 6 验证与门禁
    heading(doc, "6. 验证与质量门禁", 1)
    table(doc,
          ["门禁", "命令 / 位置", "真实结果", "说明"],
          [
              ["Java 测试", "powershell -ExecutionPolicy Bypass -File scripts/mvn.ps1 test", "34 项全部通过",
               "GradingServiceTest 14 + TextChunkerTest 6 + MigrationRunnerTest 5 + ApiFlowIntegrationTest 4 + DocumentFlowIntegrationTest 5；"
               "README §5 按两层拆分为 20 + 14 表述"],
              ["Python 测试", "services/python-ai 下 pytest -q", "174 项全部通过",
               "test_connection 16 / test_explain 12 / test_explain_references 28 / test_grade 19 / test_redaction 13 / "
               "test_auth_and_health 10 / test_url_guard 18；文件内函数名口径合计 116 项，与 README §5 的「174 项」存在口径差异（见下）"],
              ["前端类型与构建", "pnpm typecheck（vue-tsc --noEmit）；pnpm build（vue-tsc --noEmit && vite build）", "vue-tsc exit 0；vite build 成功",
               "apps/renderer/package.json 的 build 脚本即包含类型检查；构建产物 apps/renderer/dist/index.html 已存在"],
              ["端到端冒烟", "powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1", "阶段 1-5 共 79 项全量通过；阶段 6 共 24 项在真实服务上隔离运行全绿",
               "合计 103 项；单次全量运行未跑完（会长时间停在真实模型段落），见 README §8 第 9 条与 §4.3"],
          ],
          [2.1, 4.4, 3.6, 5.4], size=9)

    para(doc, "未执行项（本文档编制期间无法核实为「已通过」的验证）：", bold=True, space_before=6)
    bullet(doc, "浏览器 / Electron 窗口内的人工点击验收：未执行（未核实），本文档不声称任何页面「已通过人工验收」。")
    bullet(doc, "单次全量冒烟（103 项一次跑完）：未完成（README §8 第 9 条），因此「103 项一次性全绿」属未核实。")
    para(doc, "口径差异说明：README §5 写 Python 174 项通过，而按 services/python-ai/tests/*.py 内 def test_* 清点为 116 项。"
              "两者差异可能来自参数化用例展开或不同统计口径；本表同时列出两个数字，不做四舍五入式的统一。",
         size=10, color=MUTED)

    # ---------------------------------------------------------------- 7 ADR 索引
    heading(doc, "7. 关键设计决策索引（ADR-001 ~ ADR-013）", 1)
    table(doc,
          ["编号", "标题", "一句话结论"],
          [
              ["ADR-001", "第一版范围锁定在开发文档阶段 1-4", "阶段 1-4 完整交付 + 阶段 5 配置闭环；后续修订：阶段 6 已按 ADR-013 落地，"
                                                          "「FTS5 全文检索与资料库不存在」的结论失效"],
              ["ADR-002", "由 Electron 统一编排 Java 与 Python 子进程", "Electron 先启 Python（写端点文件）再启 Java 读端点；"
                                                                    "安全性未降低，阶段 8 可迁回 Java"],
              ["ADR-003", "题目选项采用规范化表，答案只有一个权威来源", "question_option.is_correct 由 answer.value 推导写入，"
                                                                     "请求里的 correct 只作展示"],
              ["ADR-004", "凭据存储使用 Windows DPAPI，而非 Credential Manager", "DPAPI 加密后落 secrets/*.dpapi；"
                                                                                "探针失败才降级文件并显式标注后端，不静默降级"],
              ["ADR-005", "恢复采用「登记请求 + 下次启动执行」", "避免连接池持句柄时替换数据库文件；替换前自动另存当前库，"
                                                                 "校验失败即放弃恢复"],
              ["ADR-006", "迁移前备份在连接池建立之前完成", "只有「库已存在且脚本版本更高」时才 VACUUM INTO，"
                                                            "失败即中止启动"],
              ["ADR-007", "迁移执行器自研，不引入 Flyway", "schema_version 记录脚本 SHA-256，被篡改即拒绝启动；"
                                                            "不支持 undo 与 Java 迁移类"],
              ["ADR-008", "错题练习复用「试卷快照」模型", "生成 paper_snapshot(kind='practice', paper_id=NULL)，"
                                                          "判分/结果/快照重放与试卷链路完全同构"],
              ["ADR-009", "主观题评分保留「AI 建议分 + 人工确认」两步", "AI 完成后状态仍是 pending_review，"
                                                                        "只有人工确认或改分才转 graded"],
              ["ADR-010", "LLM 配置的跨字段校验由后端补齐", "defaultProfileId 存在性与 profile id 唯一性由后端在校验之后补齐，"
                                                            "保证前后端只有一份判定"],
              ["ADR-011", "判分规则版本随结果持久化", "grading_detail_json 固定写 ruleVersion（1.0）；改规则必须提升 "
                                                      "AppInfo.GRADING_RULE_VERSION"],
              ["ADR-012", "端点除落盘外还经 stdout 中继，且禁止用 tempfile.mkstemp 写端点", "子进程写不了用户数据目录时由父进程代写；"
                                                                                            "端点行含令牌不入日志；日志编码端到端统一 UTF-8"],
              ["ADR-013", "阶段 6 本地检索：能力化迁移、trigram 分词与引用来源", "trigram 分词 + 短查询降级关键词；"
                                                                                "V3 能力化跳过；分块确定性且不重叠；引用双重过滤 + 注入防护"],
          ],
          [1.7, 5.0, 8.8], size=9)

    # ---------------------------------------------------------------- 8 附录
    heading(doc, "8. 附录", 1)

    heading(doc, "8.1 Java 端点清单（共 69 个，按控制器分组）", 2)
    para(doc, "统计口径：services/java-api/src/main/java/com/aia/answer/web/*Controller.java 中的 "
              "@GetMapping/@PostMapping/@PutMapping/@PatchMapping/@DeleteMapping 注解计数（不含非控制器类）。", size=10)
    table(doc,
          ["控制器", "基础路径", "端点数", "端点"],
          [
              ["HealthController", "（根）", "1", "GET /health"],
              ["MetaController", "/api/v1", "2", "GET /meta；GET /dashboard"],
              ["QuestionController", "/api/v1/questions", "8",
               "GET；POST；GET /{id}；PUT /{id}；POST /{id}/publish；POST /{id}/archive；GET /{id}/versions；DELETE /{id}"],
              ["PaperController", "/api/v1/papers", "8",
               "GET；POST；GET /{id}；PUT /{id}；POST /{id}/publish；POST /{id}/archive；GET /{id}/snapshots；GET /{id}/snapshots/{snapshotId}"],
              ["AttemptController", "/api/v1/attempts", "9",
               "GET；POST；GET /{id}；PUT /{id}/answers/{snapshotQuestionId}；POST /{id}/submit；GET /{id}/result；"
               "POST /{id}/abandon；POST /{id}/ai-grade；POST /{id}/answers/{snapshotQuestionId}/grade"],
              ["WrongQuestionController", "/api/v1/wrong-questions", "5",
               "GET；GET /{id}；POST /{id}/review；POST /practice；PATCH /{id}"],
              ["LlmProfileController", "/api/v1/settings/llm-profiles", "12",
               "GET；POST；PUT /{id}；DELETE /{id}；POST /{id}/credential；DELETE /{id}/credential；POST /validate；"
               "POST /test-connection；POST /import/preview；POST /import/confirm；POST /export；PUT /default"],
              ["DocumentController", "/api/v1", "10",
               "GET /documents；POST /documents/import；GET /documents/{id}；GET /documents/{id}/chunks；DELETE /documents/{id}；"
               "POST /documents/reindex；GET /search；GET /search/index；GET /system/files；POST /system/files/cleanup"],
              ["SystemController", "/api/v1/system", "9",
               "GET /info；GET /backups；POST /backup；POST /restore；POST /integrity-check；POST /backups/verify；"
               "POST /seed-demo；GET /logs；GET /logs/tail"],
              ["TaskController", "/api/v1/tasks", "4", "GET；GET /{id}；POST /{id}/retry；POST /{id}/cancel"],
              ["AiController", "/api/v1/ai", "1", "POST /questions/{id}/explain"],
              ["合计", "—", "69", "与任务书给定的分布（Ai 1 / Attempt 9 / Document 10 / Health 1 / LlmProfile 12 / "
                                "Meta 2 / Paper 8 / Question 8 / System 9 / Task 4 / WrongQuestion 5）一致"],
          ],
          [3.6, 4.0, 1.3, 6.6], size=8.5)

    heading(doc, "8.2 Python 内部接口（5 条）", 2)
    para(doc, "统计口径：services/python-ai/app/api/routes_*.py 中的路由装饰器计数。除 /health 外都要求 X-Internal-Token。", size=10)
    table(doc,
          ["方法", "路径", "用途", "鉴权"],
          [
              ["GET", "/health", "服务状态与已注册 provider", "不需要"],
              ["POST", "/api/v1/ai/explain", "题目讲解（返回 citations 与 injectionSuspected）", "X-Internal-Token"],
              ["POST", "/api/v1/ai/grade-subjective", "主观题建议评分（needsReview 恒为 true）", "X-Internal-Token"],
              ["POST", "/api/v1/ai/test-connection", "模型连接测试（恒 200 + ok/category，绝不回传密钥）", "X-Internal-Token"],
              ["GET", "/api/v1/ai/profiles/{profile_id}/probe", "端点能力探测（不带密钥）", "X-Internal-Token"],
          ],
          [1.4, 5.2, 7.0, 1.9], size=9)

    heading(doc, "8.3 数据库表与索引清单（V1 + V2 + V3）", 2)
    para(doc, "统计口径：逐条清点 db/migration 下的 CREATE TABLE / CREATE VIRTUAL TABLE / CREATE INDEX 语句。"
              "普通表 19（V1）+ 4（V2）= 23 张；FTS5 虚拟表 1 张（document_fts）；索引 17（V1）+ 6（V2）= 23 个。"
              "其中 schema_version 与 app_meta 属支撑表，其余 21 张为业务表。", size=10)

    para(doc, "V1__init.sql（19 张普通表）", bold=True, space_before=4)
    table(doc,
          ["表名", "用途"],
          [
              ["schema_version", "已应用迁移版本、applied_at 与脚本 SHA-256 校验和"],
              ["app_meta", "键值型应用元数据"],
              ["question", "题目主表（题型、状态、难度、当前版本）"],
              ["question_version", "题目版本（题干、答案 JSON、rubric、内容哈希）"],
              ["question_option", "规范化选项表（is_correct 由 answer.value 推导）"],
              ["paper", "试卷主表（状态、当前快照）"],
              ["paper_question", "草稿态试卷组成（题序、分值）"],
              ["paper_snapshot", "已发布不可变快照（kind=paper/practice）"],
              ["paper_snapshot_question", "快照题目（snapshot_json 是判分唯一依据）"],
              ["attempt", "作答主表（模式、状态、总分、时间）"],
              ["attempt_answer", "作答明细（答案、得分、判分方式与状态、判分明细）"],
              ["wrong_question", "错题（按 question_version_id 唯一，状态机与复习时间）"],
              ["wrong_question_review", "复习记录（结果、间隔变化、下次复习时间）"],
              ["async_task", "异步任务（状态、幂等键、租约、重试与错误）"],
              ["llm_profile", "LLM 档案（provider、baseUrl、模型、超时、凭据引用）"],
              ["llm_config_state", "默认档案与配置修订号（单行表）"],
              ["llm_import_preview", "导入预览暂存（payload、summary、过期时间、是否已应用）"],
              ["idempotency_record", "幂等记录（键、作用域、请求哈希、响应、过期时间）"],
              ["audit_event", "审计事件（事件类型、实体、明细）"],
          ],
          [4.4, 11.1], size=9)
    para(doc, "V1 索引（17 个）：idx_task_poll、idx_wrong_review、idx_attempt_started、idx_question_status、idx_question_type、"
              "idx_qversion_question、idx_option_version、idx_paper_status、idx_paper_question、idx_snapshot_paper、idx_snapq_snapshot、"
              "idx_answer_attempt、idx_attempt_paper、idx_wq_updated、idx_review_wq、idx_idem_expires、idx_audit_created。", size=9.5)

    para(doc, "V2__resource_library.sql（4 张普通表）", bold=True, space_before=4)
    table(doc,
          ["表名", "用途"],
          [
              ["imported_file", "导入文件元数据（sha256 唯一、体积、原始名、相对路径、解析状态）"],
              ["document", "资料（标题、状态、来源类型、分块/字符数、索引版本、parser_version、错误信息）"],
              ["document_chunk", "可追溯分块（content、content_hash、parser_version、index_version）"],
              ["index_state", "索引版本切换状态（active_version / building_version）"],
          ],
          [4.4, 11.1], size=9)
    para(doc, "V2 索引（6 个）：uq_imported_file_sha（唯一）、idx_document_status、idx_document_file、idx_chunk_document、"
              "uq_chunk_version（唯一）、idx_chunk_version。", size=9.5)

    para(doc, "V3__documents_fts.sql（1 张虚拟表）", bold=True, space_before=4)
    table(doc,
          ["对象", "定义"],
          [
              ["document_fts", "CREATE VIRTUAL TABLE document_fts USING fts5(content, chunk_id UNINDEXED, tokenize='trigram')；"
                               "头部声明 -- @requires fts5-trigram"],
          ],
          [4.4, 11.1], size=9)

    heading(doc, "8.4 渲染层页面清单（14 个）", 2)
    para(doc, "统计口径：apps/renderer/src/pages/**/*.vue（14 个文件），路由为 hash 模式（createWebHashHistory）。", size=10)
    table(doc,
          ["页面组件", "路由路径", "名称（meta.title）"],
          [
              ["dashboard/DashboardPage.vue", "/", "仪表盘"],
              ["questions/QuestionListPage.vue", "/questions", "题库"],
              ["questions/QuestionEditPage.vue", "/questions/new 与 /questions/:id/edit", "新建题目 / 编辑题目"],
              ["papers/PaperListPage.vue", "/papers", "试卷"],
              ["papers/PaperEditPage.vue", "/papers/new 与 /papers/:id/edit", "新建试卷 / 编辑试卷"],
              ["attempts/AttemptListPage.vue", "/attempts", "作答记录"],
              ["attempts/AttemptPage.vue", "/attempts/:id", "答题"],
              ["attempts/AttemptResultPage.vue", "/attempts/:id/result", "作答结果"],
              ["wrongQuestions/WrongQuestionsPage.vue", "/wrong-questions", "错题本"],
              ["DocumentsPage.vue", "/documents", "资料库"],
              ["LlmSettingsPage.vue", "/settings/llm", "LLM 设置"],
              ["SystemSettingsPage.vue", "/settings/system", "系统设置"],
              ["AboutPage.vue", "/about", "关于"],
              ["NotFoundPage.vue", "/:pathMatch(.*)*", "页面不存在"],
          ],
          [6.0, 5.6, 3.9], size=9)
    para(doc, "说明：14 个页面组件对应 16 条路由记录（QuestionEditPage 与 PaperEditPage 各承担「新建」与「编辑」两条路由）。"
              "README.md §2 写「13 个页面」，按实际清点为 14 个；本表按实际清点结果编制。", size=9.5, color=MUTED)

    return doc


def main() -> int:
    missing = [f for f, _, _ in FIGURES if not os.path.isfile(os.path.join(HERE, f))]
    if missing:
        print("缺少流程图，先运行 draw_flowcharts.py：", missing)
        return 1
    doc = build()
    doc.save(OUT_PATH)
    size = os.path.getsize(OUT_PATH)
    print(f"已生成：{OUT_PATH}")
    print(f"字节数：{size}")
    print(f"段落数：{len(doc.paragraphs)}  表格数：{len(doc.tables)}  节数：{len(doc.sections)}")
    print("表格维度：" + "; ".join(f"{i + 1}:{len(t.rows)}x{len(t.columns)}" for i, t in enumerate(doc.tables)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
