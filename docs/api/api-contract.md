# AI 智能答题系统 · v1 接口契约（冻结版）

> 本文件是 v1 实现的契约基准，Java 业务服务、Python AI 服务、Vue 前端必须同时遵守。
> 机器可读版本：`contracts/openapi/openapi.yaml`（Java API）、`contracts/json-schema/llm-config.schema.json`（LLM 配置）。
> 冻结日期：v1 阶段 1-4；阶段 6（本地检索/资料库）按本文件 §11 扩展。任何改动先改本文件，再改实现。

## 0. 通用约定

- 传输：HTTP/1.1，仅绑定 `127.0.0.1`（或 `::1`），不允许对外网卡监听。
- 前缀：业务 API 统一 `/api/v1`；`/health` 不鉴权且不参与版本前缀。
- 认证：除 `/health` 外所有请求必须带 `X-Session-Token: <token>`。令牌由 Java 服务启动时随机生成（256bit hex），只在本进程生命周期内有效，落盘位置 `<data-dir>/runtime/java-endpoint.json`，退出即失效。缺失/错误令牌返回 401 + `UNAUTHORIZED`。
- Host 校验：`Host` 头必须是 `127.0.0.1:<port>` 或 `localhost:<port>`；`Origin` 若存在，必须是 `http://127.0.0.1:<port>`、`http://localhost:<port>` 或 Electron 渲染进程的 `file://`/`app://` 来源。否则 403 + `ORIGIN_REJECTED`。
- 编码：请求与响应均为 `application/json; charset=utf-8`。时间统一 UTC ISO-8601（`2026-10-10T14:00:00Z`）。
- `requestId`：形如 `req_` + 26 位小写 Crockford Base32（ULID），由各服务自行生成，**不跨服务传递**。Python 内部接口使用 `req_` + 16 位十六进制，仅用于其自身日志关联。
- JSON 字段不省略：Java 侧配置 `default-property-inclusion: always`，可空字段显式返回 `null`（前端不能靠「字段缺失」判断状态）。
- 命名：JSON 字段一律 camelCase；数据库列名 snake_case，由 DAO 层映射。
- 分页：`page` 从 1 开始，`size` 默认 20、最大 100。响应带 `page/size/total/totalPages`。

### 0.1 成功响应包络

```json
{
  "success": true,
  "data": { },
  "error": null,
  "requestId": "req_01HQ...",
  "timestamp": "2026-10-10T14:00:00Z"
}
```

### 0.2 失败响应包络

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "题干不能为空",
    "details": [{ "field": "stem", "message": "must not be blank" }],
    "retryable": false
  },
  "requestId": "req_01HQ...",
  "timestamp": "2026-10-10T14:00:00Z"
}
```

`message` 面向用户；`details` 可省略；任何响应都不得包含密钥、凭据引用之外的敏感值或供应商原始错误全文。

### 0.3 错误码（v1 冻结）

| code | HTTP | 含义 | 客户端行为 |
| --- | --- | --- | --- |
| `VALIDATION_ERROR` | 400 | 字段校验失败 | 定位到字段提示修正 |
| `UNAUTHORIZED` | 401 | 令牌缺失或失效 | 由桌面色壳刷新运行时会话 |
| `ORIGIN_REJECTED` | 403 | Host/Origin 不可信 | 不重试，提示安全策略拦截 |
| `RESOURCE_NOT_FOUND` | 404 | 资源不存在 | 刷新列表 |
| `STATE_CONFLICT` | 409 | 状态不允许当前操作 | 展示当前状态并刷新 |
| `VERSION_CONFLICT` | 409 | 乐观锁/版本号冲突 | 提示数据已变化 |
| `IDEMPOTENCY_CONFLICT` | 409 | 同一幂等键不同载荷 | 提示重复提交 |
| `DB_BUSY` | 503 | SQLite 锁冲突 | 短暂重试，超限后提示稍后 |
| `DB_MIGRATION_FAILED` | 500 | 迁移失败 | 停止启动，提供恢复路径 |
| `CONFIG_JSON_INVALID` | 400 | JSON 语法错误 | 显示行列位置 |
| `CONFIG_SCHEMA_UNSUPPORTED` | 400 | 配置版本不支持 | 提示升级应用 |
| `BASE_URL_BLOCKED` | 400 | 目标地址被安全策略拒绝 | 说明地址类别 |
| `CREDENTIAL_MISSING` | 400 | 该 profile 未配置 apiKey | 引导在 `<UserData>/config/setting.json` 中填写 apiKey（见 §6） |
| `CONFIG_FILE_INVALID` | 400 | 配置文件（setting.json）校验失败 | 展示 schema path 与原因，修正后调用 `reload`（§6） |
| `LLM_AUTH_FAILED` | 502 | LLM 鉴权失败 | 检查密钥，不自动覆盖配置 |
| `LLM_CONNECT_TIMEOUT` | 504 | 连接超时 | 检查端点/网络/超时 |
| `LLM_UNAVAILABLE` | 503 | AI 服务或模型不可用 | 提示 AI 能力降级，主流程不受影响 |
| `TASK_RETRY_EXHAUSTED` | 409 | 任务重试耗尽 | 展示重试/删除/详情 |
| `DOCUMENT_FORMAT_UNSUPPORTED` | 400 | 资料格式不在首期白名单（仅 `.txt`/`.md`） | 提示支持的格式；PDF/DOCX 属阶段 7 |
| `DOCUMENT_TOO_LARGE` | 400 | 资料超过导入体积上限 | 提示上限，不重试 |
| `DOCUMENT_PARSE_FAILED` | 400 | 文件读取/暂存阶段失败（尚未入库） | 展示失败详情，可更换文件重试；落盘后才解析失败的情形见 §11.5 |
| `DOCUMENT_INDEX_FAILED` | 500 | 索引重建失败（旧索引版本保留） | 提示检索仍可用旧版本，可重试 |
| `INTERNAL_ERROR` | 500 | 未预期错误 | 展示 requestId 便于诊断 |

### 0.4 幂等

创建/提交/任务触发类接口支持 `Idempotency-Key` 请求头（1-128 字符）。同一键 + 同一载荷重复调用返回首次结果；同键不同载荷返回 `IDEMPOTENCY_CONFLICT`。键与结果的对应关系持久化在 `idempotency_record` 表（保留 7 天）。

### 0.5 枚举

- `questionType`: `single_choice` | `multiple_choice` | `true_false` | `fill_blank` | `short_answer` | `essay`
- `questionStatus` / `paperStatus`: `draft` | `published` | `archived`
- `attemptStatus`: `in_progress` | `submitted` | `graded` | `abandoned`
- `gradingMethod`: `exact` | `normalized` | `partial_credit` | `ai_rubric` | `manual` | `ungraded`
- `gradingStatus`: `graded` | `pending_review` | `failed`
- `wrongStatus`: `active` | `reviewing` | `mastered` | `archived`
- `taskStatus`: `queued` | `running` | `succeeded` | `retry_wait` | `failed` | `cancelled`

---

## 1. 健康与元信息

### GET /health（不鉴权）
`200` → `{ "status": "UP", "service": "java-api", "version": "1.0.0", "schemaVersion": 3, "uptimeSeconds": 42 }`

### GET /api/v1/meta
`data`:
```json
{
  "appVersion": "1.0.0",
  "contractVersion": "1.0",
  "schemaVersion": 3,
  "dataDir": "C:\\Users\\x\\AppData\\Roaming\\ai-answer-desktop",
  "databasePath": ".../database/app.db",
  "databaseSizeBytes": 245760,
  "fts5Available": true,
  "aiService": { "available": true, "baseUrl": "http://127.0.0.1:53123", "latencyMs": 12, "message": "版本 1.0.0" },
  "startedAt": "2026-10-10T13:59:00Z"
}
```

说明：`fts5Available` 与 `contractVersion` **只在 `/meta`** 返回；`/system/info` 返回运行与维护信息。
`fts5Available` 表示**检索索引可用**：即 SQLite 构建同时支持 FTS5 与 `trigram` 分词器（见 §11.4）；
为 `false` 时检索自动降级为关键词实现，`/api/v1/search` 的 `mode` 会返回 `keyword`（ADR-013）。
`aiService` 在 `/meta`、`/dashboard`、`/system/info` 三处的形状一致（`available/baseUrl/latencyMs/message`），
`available=false` 时 `baseUrl` 可能为 `null`，`message` 给出可读原因。

### GET /api/v1/dashboard
`data`: `{ "questionCount": 12, "publishedQuestionCount": 10, "paperCount": 3, "publishedPaperCount": 2, "inProgressAttemptCount": 1, "dueWrongQuestionCount": 4, "activeWrongQuestionCount": 7, "masteredWrongQuestionCount": 2, "recentAttempts": [AttemptSummary], "recentWrongQuestions": [WrongQuestion], "aiService": { "available": true, "baseUrl": "http://127.0.0.1:53123", "latencyMs": 12, "message": "版本 1.0.0" } }`

`recentAttempts` / `recentWrongQuestions` 各取最近 5 条。

`AttemptSummary` = `{ "id", "paperId", "paperTitle", "status", "totalScore", "maxScore", "questionCount", "startedAt", "submittedAt", "gradedAt" }`

---

## 2. 题库与题目版本

### 2.1 对象

`QuestionSummary`：
```json
{
  "id": "q_01HQ...", "title": "TCP 三次握手", "questionType": "single_choice",
  "status": "published", "currentVersionNo": 2, "knowledgeTags": ["网络", "TCP"],
  "difficulty": 2, "updatedAt": "2026-10-10T14:00:00Z"
}
```

`Question`（详情，含当前版本内容）：
```json
{
  "id": "q_01HQ...", "title": "TCP 三次握手", "questionType": "single_choice",
  "status": "published", "difficulty": 2,
  "currentVersion": {
    "id": "qv_01HQ...", "versionNo": 2, "stem": "TCP 建立连接需要几次握手？",
    "options": [
      { "key": "A", "content": "1 次", "correct": false },
      { "key": "B", "content": "2 次", "correct": false },
      { "key": "C", "content": "3 次", "correct": true }
    ],
    "answer": { "type": "single_choice", "value": "C" },
    "explanation": "三次握手确保双方收发能力与初始序号同步。",
    "rubric": null, "knowledgeTags": ["网络", "TCP"], "createdAt": "2026-10-10T14:00:00Z",
    "changeNote": "补充解析"
  },
  "versions": [ { "id": "qv_...", "versionNo": 1, "createdAt": "...", "changeNote": "初稿" } ],
  "createdAt": "...", "updatedAt": "..."
}
```

`answer` 按题型：
- `single_choice`：`{ "type": "single_choice", "value": "C" }`
- `multiple_choice`：`{ "type": "multiple_choice", "value": ["A","C"], "partialCredit": false }`
- `true_false`：`{ "type": "true_false", "value": true }`
- `fill_blank`：`{ "type": "fill_blank", "blanks": [ { "index": 1, "accepted": ["TCP"], "normalize": { "ignoreCase": true, "trim": true, "ignorePunctuation": true, "ignoreWhitespace": true } } ] }`
- `short_answer` / `essay`：`{ "type": "short_answer", "referenceAnswer": "…", "rubric": { "criteria": [ { "id": "c1", "desc": "指出三次握手顺序", "weight": 60 }, { "id": "c2", "desc": "说明目的", "weight": 40 } ], "maxScore": 10 } }`

### 2.2 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/questions` | 查询参数：`page,size,q,questionType,status,tag,sort`（`sort` ∈ `updatedAt,-updatedAt,createdAt,-createdAt`，默认 `-updatedAt`），白名单以外参数返回 `VALIDATION_ERROR`。`data`: `{ items: [QuestionSummary], page, size, total, totalPages }` |
| POST | `/api/v1/questions` | 建题（同时建 version 1）。body 见下。返回 `201` + `Question` |
| GET | `/api/v1/questions/{id}` | 返回 `Question`（`?versionNo=` 可读历史版本） |
| PUT | `/api/v1/questions/{id}` | 修改。请求体同 POST + 可选 `changeNote`。**若题干/选项/答案/rubric/标签任一变化**，创建新 `question_version` 并把 `current_version_id` 指向它；否则只更新 `question.title`/`difficulty`。返回 `Question`。并发用 `expectedVersionNo` 做乐观锁，不匹配返回 `VERSION_CONFLICT` |
| POST | `/api/v1/questions/{id}/publish` | `draft`→`published`。已发布题目不可原地修改版本内容 |
| POST | `/api/v1/questions/{id}/archive` | 归档；被试卷/作答/错题引用时不物理删除 |
| GET | `/api/v1/questions/{id}/versions` | 版本列表 |
| DELETE | `/api/v1/questions/{id}` | 仅允许删除从未被引用且从未发布的草稿，否则 `STATE_CONFLICT` |

POST/PUT body：
```json
{
  "title": "TCP 三次握手",
  "questionType": "single_choice",
  "difficulty": 2,
  "stem": "TCP 建立连接需要几次握手？",
  "options": [ { "key": "A", "content": "1 次", "correct": false } ],
  "answer": { "type": "single_choice", "value": "C" },
  "explanation": "…",
  "knowledgeTags": ["网络"],
  "changeNote": "初稿",
  "expectedVersionNo": 1
}
```

校验：`title` 1-200；`stem` 1-5000；选择题必须 2-10 个选项且 `key` 唯一、`answer.value` 必须命中 `correct: true` 的选项集合；`true_false` 无选项；`fill_blank` 至少 1 个空；`short_answer`/`essay` 需 `rubric.criteria` 权重合计为 100 或提供 `maxScore`。

---

## 3. 试卷与发布快照

`PaperSummary` = `{ "id", "title", "status", "questionCount", "totalScore", "publishedAt", "createdAt", "updatedAt", "latestSnapshotId" }`

`Paper` = `{ ...PaperSummary, "description", "questions": [ { "id", "questionId", "questionVersionId", "orderNo", "score", "title", "questionType", "versionNo" } ] }`

`PaperSnapshot` = `{ "id", "paperId", "versionNo", "title", "description", "questionCount", "totalScore", "publishedAt", "questions": [ { "id", "orderNo", "score", "questionId", "questionVersionId", "questionType", "stem", "options", "answer", "explanation", "rubric", "knowledgeTags" } ] }`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/papers` | `page,size,q,status` |
| POST | `/api/v1/papers` | `{ "title", "description", "questions": [ { "questionId", "questionVersionId"?, "score"? } ] }`，默认每题 5 分 |
| GET | `/api/v1/papers/{id}` | `Paper` |
| PUT | `/api/v1/papers/{id}` | 仅 `draft` 可改组成与分值；否则 `STATE_CONFLICT` |
| POST | `/api/v1/papers/{id}/publish` | 生成不可变快照（题数 ≥1，分值 >0，引用题目版本必须存在且未归档）。返回 `PaperSnapshot` |
| GET | `/api/v1/papers/{id}/snapshots` | 按版本号倒序返回该试卷的全部发布快照 |
| GET | `/api/v1/papers/{id}/snapshots/{snapshotId}` | 读取历史快照 |
| POST | `/api/v1/papers/{id}/archive` | 归档 |

发布后旧快照与既有作答结果永不改变。

---

## 4. 答题与判分

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/attempts` | `{ "paperId" }` 或 `{ "mode": "wrong_practice", "wrongQuestionIds": [...] }`。引用**已发布**快照创建 attempt，返回 `AttemptSheet` |
| GET | `/api/v1/attempts` | `page,size,status,paperId` |
| GET | `/api/v1/attempts/{id}` | `AttemptSheet`（未提交时不含答案/解析） |
| PUT | `/api/v1/attempts/{id}/answers/{snapshotQuestionId}` | `{ "answer": <Answer> }` 幂等保存，返回 `AttemptAnswer`。`attempt.status` 必须 `in_progress` |
| POST | `/api/v1/attempts/{id}/submit` | 幂等提交：先持久化全部作答 → 确定性判分 → 生成错题记录。返回 `AttemptResult` |
| GET | `/api/v1/attempts/{id}/result` | `AttemptResult`（历史结果基于当时快照） |
| POST | `/api/v1/attempts/{id}/abandon` | 放弃 |
| POST | `/api/v1/attempts/{id}/ai-grade` | 为本次作答所有 `pending_review` 主观题排队 AI 评分任务（幂等键 `ai_grade:{answerId}`）。返回 `{ "taskIds": [...], "pendingCount": n }`；AI 不可用时返回 `LLM_UNAVAILABLE`，已保存的作答不受影响。任务完成后分数写入 `gradingDetail.suggestedScore`，但状态**仍是** `pending_review` |
| POST | `/api/v1/attempts/{id}/answers/{snapshotQuestionId}/grade` | 人工确认或覆盖主观题分数：`{ "score": 10, "note": "可选" }` → 返回 `AttemptResult`（总分已重算，`gradingMethod=manual`、`gradingStatus=graded`）。AI 建议分只有经过这一步才生效 |
| POST | `/api/v1/ai/questions/{questionId}/explain` | 题目讲解：`{ "userAnswer"?, "profileId"? }` → `{ explanation, tips, model, usage, latencyMs, profileRef }`。使用默认档案时省略 `profileId` |

`AttemptResult` 额外返回 `correctCount`、`wrongCount`、`unansweredCount`、`pendingReviewCount`，便于结果页直接展示。

`AttemptSheet` = `{ "id", "paperId", "paperSnapshotId", "paperTitle", "mode", "status", "totalScore", "maxScore", "startedAt", "submittedAt", "gradedAt", "answers": [AttemptAnswer], "questions": [ { "snapshotQuestionId", "orderNo", "score", "questionType", "stem", "options", "knowledgeTags" } ] }`（`status != in_progress` 时附带 `answer`/`explanation`）

`AttemptAnswer` = `{ "snapshotQuestionId", "questionId", "questionVersionId", "answer", "score", "maxScore", "gradingMethod", "gradingStatus", "gradingDetail", "savedAt" }`

`AttemptResult` = `{ "attempt": AttemptSummary, "items": [ { "snapshotQuestionId", "orderNo", "questionType", "stem", "options", "userAnswer", "correctAnswer", "explanation", "score", "maxScore", "correct", "gradingMethod", "gradingStatus", "gradingDetail" } ] }`

### 4.1 判分规则（确定性，v1 冻结）

| 题型 | 规则 | `gradingMethod` |
| --- | --- | --- |
| `single_choice` | 选项集合完全一致 | `exact` |
| `true_false` | 布尔一致 | `exact` |
| `multiple_choice` | 默认全对得分（集合相等）；`answer.partialCredit=true` 时按 `(命中 - 错误)/正确数` 归一化并夹到 `[0,1]` | `exact` / `partial_credit` |
| `fill_blank` | 每空按 `normalize` 归一化后与 `accepted` 任一项比较；得分 = 分值 × 命中空数/总空数 | `normalized` |
| `short_answer`/`essay` | 提交时标记 `gradingStatus=pending_review`，`score=null`；由 AI rubric 评分或人工复核写入建议分 | `ai_rubric` / `manual` |
| 未作答 | `score=0`，`gradingMethod=ungraded`，计入错题 | `ungraded` |

判分必须记录：标准答案版本（`questionVersionId`）、用户答案、得分、判分规则版本（`grading_detail.ruleVersion`，v1 = `"1.0"`）。

---

## 5. 错题闭环

`WrongQuestion` = `{ "id", "questionId", "questionVersionId", "questionType", "title", "stem", "status", "wrongCount", "correctStreak", "lastWrongAt", "nextReviewAt", "lastScore", "maxScore", "createdAt", "updatedAt" }`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/wrong-questions` | `page,size,status,dueOnly,tag,questionType,sort`（`dueOnly=true` 时 `next_review_at <= now`） |
| GET | `/api/v1/wrong-questions/{id}` | 详情 + 最近 20 条复习记录 |
| POST | `/api/v1/wrong-questions/{id}/review` | `{ "result": "correct"\|"wrong", "note"?, "durationSeconds"?, "attemptAnswerId"? }`。写入 `wrong_question_review` 并更新状态与调度 |
| POST | `/api/v1/wrong-questions/practice` | `{ "wrongQuestionIds"?, "limit"? }`（缺省取全部到期错题）。创建 `mode=wrong_practice` 的 attempt |
| PATCH | `/api/v1/wrong-questions/{id}` | `{ "status": "mastered"\|"archived"\|"active" }` 手动干预 |

`WrongQuestionDetail` = `{ "question": WrongQuestion, "reviews": [ReviewRecord] }`（默认最近 20 条，按时间倒序）

`ReviewRecord` = `{ "id", "result": "correct"|"wrong", "note", "intervalDaysBefore", "intervalDaysAfter", "nextReviewAt", "durationSeconds", "reviewedAt" }`

`WrongQuestion` 中的 `currentVersionNo` 与 `wrongVersionNo` 用于提示版本差异：两者不同说明题目已改义，
统计意义可能发生变化，UI 必须显式提示。

状态机与调度（v1 冻结，可解释规则）：
- 答错：`wrong_count += 1`、`correct_streak = 0`、`status = active`、`last_wrong_at = now`、`next_review_at = now + interval(wrong_count)`
- 复习正确：`correct_streak += 1`；`correct_streak >= 2` → `status = mastered`、`next_review_at = null`；否则 `status = reviewing`、`next_review_at = now + interval(wrong_count)`
- 复习错误：回到 `active`、`correct_streak = 0`
- `interval(n)`：n=1→1 天，n=2→3 天，n=3→7 天，n≥4→15 天
- 去重键：`question_version_id`（唯一约束）；`wrong_question_review` 记录每次复习，掌握状态变化可追溯

---

## 6. LLM 配置（文件驱动，v1.1）

**配置源是文件，不是数据库**：`<UserData>/config/setting.json` 是 LLM 配置与用户偏好的唯一权威，
Schema 唯一来源是 `contracts/json-schema/setting.schema.json`。Java 侧 `ConfigService` 缓存该文件并支持热加载；
SQLite 不再读写任何 LLM 配置（`llm_profile`/`llm_config_state`/`llm_import_preview` 三张表**保留结构但不再使用**，
仅作为首次启动一次性迁移的来源，迁移后写 `app_meta.llm_config_migrated_at` 标记，绝不覆盖已存在的 setting.json）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/settings/llm-profiles` | `{ profiles: [ProfileView], defaultProfileId, configRevision, configSource, configPath, configUpdatedAt, warnings: [{ code, message, path? }] }`。`configSource` 固定为 `setting.json`；`warnings` 是热加载/校验问题（非法时保留上一份有效配置，服务照常可用） |
| POST | `/api/v1/settings/llm-profiles` | 新建 profile：`{ profile: ProfileInput }` → 写 setting.json（原子替换），`configRevision` 变化；`201` |
| PUT | `/api/v1/settings/llm-profiles/{id}` | 保存 profile（`ProfileInput`）。**`apiKey` 三态**：字段省略=保持原值；`""` 或 `null`=清除；非空=替换。校验失败不落盘，保留上一有效配置 |
| DELETE | `/api/v1/settings/llm-profiles/{id}` | 删除；正在使用的默认 profile 需先切换，否则 `STATE_CONFLICT`。删除后文件内不再存在该 profile（密钥随条目一并移除） |
| POST | `/api/v1/settings/llm-profiles/{id}/credential` | **语义变更（路径保留）**：等价于「只更新该 profile 的 apiKey」，写入 setting.json；不再使用 OS 凭据存储。响应不回显密钥 |
| DELETE | `/api/v1/settings/llm-profiles/{id}/credential` | 清除该 profile 的 apiKey（等价于 PUT 时 `apiKey: ""`） |
| POST | `/api/v1/settings/llm-profiles/validate` | 纯校验，不写文件、不发网络请求：`{ "config": <setting.json 或仅 llm 段> }` → `{ valid, errors: [{ path, message }], summary: { profileCount, providers } }`。跨字段规则：`defaultProfile` 必须存在、`id` 必须唯一 |
| POST | `/api/v1/settings/llm-profiles/test-connection` | `{ "profile": ProfileInput }` 或 `{ "profileId": "..." }`；`profileId` 形式时密钥从 setting.json 取。短超时、最小请求，返回 `{ ok, category, latencyMs, httpStatus?, modelEcho?, message, suggestion }`；`category` ∈ `ok\|dns\|tls\|auth\|model_not_found\|quota\|timeout\|protocol\|blocked\|unavailable`。不回传请求头与密钥 |
| POST | `/api/v1/settings/llm-profiles/import/preview` | `{ "config": <setting.json 或 llm-config.json> }` → `{ previewId, summary, changes: [{ action: "create"\|"update"\|"unchanged"\|"conflict", profileId, name, fields: [] }], errors: [] }`；**只在内存中预览**（不再落表），不修改当前配置；`previewId` 10 分钟过期 |
| POST | `/api/v1/settings/llm-profiles/import/confirm` | `{ "previewId" }` → 应用变更并原子写入 setting.json；`previewId` 过期/不存在返回 `STATE_CONFLICT` |
| POST | `/api/v1/settings/llm-profiles/export` | **完整配置导出**：`{ "includeSecrets": false }`（默认）→ 返回与 setting.json 同构的文档（`schemaVersion/application/llm`），其中每个 profile 的 `apiKey` 置 `null`（`setting.schema.json` 允许 `string \| null`，因此**脱敏导出文件本身就是 Schema 合法的，可直接再导入/再校验**）；`includeSecrets: true` 才包含明文密钥，响应带 `containsSecrets: true`、建议文件名 `setting-with-keys.json`、并写审计事件。任何情况下都不含内部令牌 |
| PUT | `/api/v1/settings/llm-profiles/default` | `{ "profileId": "..." \| null }` 切换默认；置 null 需显式 `{ "disableAi": true }` |
| POST | `/api/v1/settings/llm-profiles/reload` | 手动重读 setting.json 并刷新缓存 → `{ configRevision, warnings }`；失败返回 `400 CONFIG_FILE_INVALID`（消息含 JSON 行列号或 schema path，均放入 `details`，缓存仍保留上一份有效配置）。**实现约定**：JSON 语法错误与 Schema/跨字段失败目前统一返回 `CONFIG_FILE_INVALID`（§0.3 的 `CONFIG_JSON_INVALID` 保留给导入解析路径）；若将来要按层区分，把语法错误改为返回 `CONFIG_JSON_INVALID` 即可，语义与 last-good 行为不变 |

`ProfileView` = `{ "id", "name", "provider", "baseUrl", "model", "apiKeyMasked", "apiKeyConfigured", "temperature", "timeoutSeconds", "connectTimeoutSeconds", "allowLocalNetwork", "parameters": {}, "isDefault" }`
`ProfileInput` = 与 `ProfileView` 同字段，但用 `apiKey`（见上表三态语义）替代 `apiKeyMasked`/`apiKeyConfigured`。

- **`temperature` 是 profile 级字段**（与 `setting.schema.json` 一致，取值 0–2），与 `parameters` 段是两个位置、不要混用：
  `parameters` 只放供应商特定参数（`topP`/`maxTokens`/`stream`）。Java 在构造发给 Python 的 `LlmCallProfile` 时，
  若 `parameters` 未显式给出温度，会把 profile 级 `temperature` 注入 `parameters.temperature`（Python 只认后者），
  因此用户填的温度对真实调用生效；这一点是实现约定，不影响本接口的字段语义。

- `apiKeyMasked` 有值时恒为 `"******"`，绝不回显明文；`apiKeyConfigured=false` 且 provider 需要密钥时，调用会返回 `CREDENTIAL_MISSING`（语义已改为「该 profile 未配置 apiKey」）。
- 热加载：文件变化（去抖后）→ 校验 → 通过则替换内存缓存并更新 `configRevision`；失败则**保留上一份有效配置**并把原因放进 `warnings`。

安全边界（配置模型已由「OS 凭据存储」改为「本机配置文件」，对应 ADR-014 取代 ADR-004）：
- 本系统定位为**个人单机应用**：明文 `apiKey` 存放在 `<UserData>/config/setting.json`（写权限收紧到 0600），用户需自行保证本机账户安全。
- 密钥**只**出现在该文件、内存缓存与「显式选择含密钥导出」的结果中；API 响应、日志、异常栈、审计、任务 payload、诊断包一律掩码或剔除（`authorization`、`api_key`、`apikey`、`token`、`secret`、`password`）。
- 导出默认不含明文密钥；含密钥导出必须显式传参 + 客户端二次确认 + 审计留痕。
- 连接测试与运行时调用仍适用原有的 `baseUrl` 安全规则：
- 解析 `baseUrl` 的协议/端口/域名与解析后 IP；`allowLocalNetwork=false` 时拒绝 loopback、RFC1918 私网、链路本地 `169.254.0.0/16`、`::1`、云元数据 `169.254.169.254`、`100.100.100.200`。
- 每一跳重定向都重新校验目标；默认不跟随跨主机重定向。
- 限制超时与响应体大小（默认 1 MiB）。
- 错误分类区分 DNS / TLS / 鉴权 / 模型不存在 / 额度 / 超时 / 协议不兼容。
- 日志、异常栈、任务 payload 对 `authorization`、`api_key`、`apikey`、`token`、`secret`、`password` 一律脱敏。

---

## 7. AI 能力（Python 服务 · 内部接口）

Python AI 服务只监听 `127.0.0.1`，仅由 Java 调用，使用与 Java 运行时生成的独立内部令牌 `X-Internal-Token`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | `{ "status": "UP", "service": "python-ai", "version": "1.0.0", "providers": ["openai-compatible","ollama"] }` |
| POST | `/api/v1/ai/explain` | `{ "question": {stem, questionType, options, answer, explanation}, "userAnswer"?, "references"?: [{refId, title, pageNo, content}], "profile": LlmCallProfile, "locale": "zh-CN" }` → `{ "explanation", "tips": [], "citations": [{refId, used}], "injectionSuspected": boolean, "model", "usage": {}, "latencyMs", "profileRef" }` |
| POST | `/api/v1/ai/grade-subjective` | `{ "question": {stem, referenceAnswer, rubric}, "userAnswer", "profile", "maxScore" }` → `{ "suggestedScore", "maxScore", "criteria": [{id, score, reason}], "confidence", "needsReview": true, "model", "latencyMs", "profileRef" }` |
| POST | `/api/v1/ai/test-connection` | `{ "profile", "apiKey"? }` → 与 Java `test-connection` 同结构（Java 转发并统一错误分类） |
| GET | `/api/v1/ai/profiles/{id}/probe` | 探测端点能力（`/models` 是否可用等），不使用密钥 |

`LlmCallProfile` = `{ "id", "provider", "baseUrl", "model", "timeoutSeconds", "connectTimeoutSeconds", "allowLocalNetwork", "parameters": {}, "apiKey": "<每次调用从 setting.json 读取，只在内存中传给 Python；Python 不落盘、不缓存>" }`

> `profileRef` 出现在讲解与评分响应里，形如 `profile://<profileId>`，只是「这次调用用的是哪个档案」的标识，
> **不是密钥也不是凭据引用**；密钥仅存在于本机 setting.json 与调用期内存中，任何接口都不会回显它。
> `allowLocalNetwork` 必须随档案一并传到 Python：本地模型端点若未声明该字段，会被 SSRF 防护判为 `BASE_URL_BLOCKED`。

AI 不可用时：答题、判分、错题闭环主流程必须继续工作；AI 相关接口返回 `LLM_UNAVAILABLE` 并保持 `gradingStatus=pending_review`。

---

## 8. 系统设置

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/system/info` | 数据目录、日志目录、数据库路径与大小、schemaVersion、备份列表、运行时长 |
| POST | `/api/v1/system/backup` | 立即备份：`VACUUM INTO` 生成一致性快照到 `<data>/backups/`，返回 `{ fileName, sizeBytes, checksum, createdAt, integrityOk, schemaVersion, appVersion, kind }`（`kind` ∈ `manual`/`auto`/`pre-migration`）。备份未通过完整性校验时立即删除并报错，不留可疑文件 |
| GET | `/api/v1/system/backups` | 备份列表（同上字段） |
| POST | `/api/v1/system/backups/verify?fileName=<name>` | 对指定备份做只读校验 → `{ ok, integrityCheck, foreignKeyCheck, schemaVersion, databaseSizeBytes }` |
| POST | `/api/v1/system/restore` | `{ "fileName", "confirm": true }` → 校验备份（`integrity_check` + `foreign_key_check` + `schema_version` 存在），写 `restore-request.json`，返回 `{ pendingRestore: true, requiresRestart: true, backup }`。真正的替换在下次启动、打开连接池之前执行，替换前自动备份当前库 |
| POST | `/api/v1/system/integrity-check` | 返回 `{ ok, integrityCheck, foreignKeyCheck, schemaVersion, databaseSizeBytes }` |
| POST | `/api/v1/system/seed-demo` | 幂等写入演示题库（6 题覆盖 6 种题型 + 1 张已发布试卷），已存在则跳过。返回 `{ questionsCreated, papersCreated, skipped }` |
| GET | `/api/v1/system/logs` | 日志文件列表：`[ { fileName, sizeBytes, modifiedAt } ]` |
| GET | `/api/v1/system/logs/tail?file=<name>` | 返回该文件尾部 N 行（默认 500，脱敏后）：`{ fileName, lines: string[] }` |
| GET | `/api/v1/system/info` | 返回 `{ appVersion, schemaVersion, dataDir, logDir, databasePath, databaseSizeBytes, backupCount, backups[], uptimeSeconds, configSource, configPath, aiService }`；`configSource` 固定为 `setting.json`、`configPath` 是该文件的绝对路径（见 §6），UI 必须显式展示 |

---

## 9. 异步任务

`AsyncTask` = `{ "id", "taskType", "status", "payload", "result", "attemptCount", "maxAttempts", "leaseOwner", "leaseUntil", "heartbeatAt", "nextRunAt", "lastErrorCode", "lastErrorMessage", "createdAt", "updatedAt" }`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/tasks` | `page,size,status,taskType` |
| GET | `/api/v1/tasks/{id}` | 详情 |
| POST | `/api/v1/tasks/{id}/retry` | `failed`/`cancelled` → `queued`（重置 `attempt_count=0`、`next_run_at=now`） |
| POST | `/api/v1/tasks/{id}/cancel` | `queued`/`running`/`retry_wait` → `cancelled` |

状态机、原子认领、租约恢复、指数退避 + 抖动见开发文档 §8；v1 已实现 `ai_grade` 任务类型（主观题评分），其余类型为占位。

---

## 10. 桌面壳接口（Electron preload 白名单）

`window.app` 仅暴露以下方法，禁止暴露 `ipcRenderer`、Node 全局对象或任意命令执行：

| 方法 | 返回 |
| --- | --- |
| `getVersion()` | `string` |
| `getRuntime()` | `{ javaBaseUrl, sessionToken, pythonBaseUrl?, dataDir, logDir, appVersion, platform, status, warnings }` |
| `getHealth()` | `{ java: { reachable, status, message }, python: {...}, runtime }` |
| `selectJsonFile()` | `{ canceled, filePath?, content?, name? }`（超过 1 MiB 返回 `error`） |
| `selectImportFile()` | `{ canceled, filePath?, name?, sizeBytes?, sha256? }` |
| `exportConfigFile(defaultName, content)` | `{ canceled, filePath? }` |
| `openPath(target)` | `{ ok, message? }`（仅允许数据目录、日志目录、运行目录或其子路径） |
| `showLogs()` | `{ ok, message? }` |
| `restartServices()` | `{ ok, runtime }`（用于恢复备份后重启服务） |
| `onServiceStatus(cb)` | 取消订阅函数；事件为完整 runtime 快照 |
| `onServiceRestarted(cb)` | 取消订阅函数；服务重启完成后推送新快照 |

补充约定：preload 还会把同一份运行时会话以**同步只读全局变量** `window.__APP_RUNTIME__` 暴露，
渲染层在初始化阶段无需等待异步 IPC；两条路径返回同一份数据，取值优先级为
`window.__APP_RUNTIME__` → `window.app.getRuntime()` → 开发服务器 `/__app_runtime`。

安全约束：`sessionToken` 只在内存与本机端点文件中存在，进程退出即失效；接口、日志与诊断包都不得回显它。

---

## 11. 资料库与本地检索（阶段 6）

资料原文与分块元数据存放在普通表（`imported_file`/`document`/`document_chunk`/`index_state`），FTS5 虚拟表
`document_fts` 只保存可检索文本与 `chunk_id`，**不作为原文的唯一存储**（开发文档 §10.1）。
检索层通过 `DocumentSearchPort` 抽象：FTS5 不可用时自动降级为关键词检索，**接口形状与业务层不变**
（开发文档 §10.1、§12.2；索引与分词取舍见 ADR-013）。

### 11.1 对象

`DocumentSummary` = `{ "id", "title", "status", "sourceKind", "originalFileName", "sizeBytes", "sha256", "chunkCount", "charCount", "indexVersion", "parserVersion", "errorCode", "errorMessage", "createdAt", "updatedAt" }`
`ChunkView` = `{ "id", "documentId", "chunkIndex", "pageNo", "content", "contentHash", "parserVersion", "indexVersion", "createdAt" }`
`SearchHit` = `{ "documentId", "chunkId", "title", "pageNo", "chunkIndex", "snippet", "score", "matchMode" }`
`IndexState` = `{ "indexName", "activeVersion", "buildingVersion", "updatedAt", "fts5Available", "documentCount", "chunkCount" }`

- `status` ∈ `imported` | `indexed` | `failed`；`sourceKind` ∈ `text` | `markdown`
- `snippet` 里的命中片段用 `[[` / `]]` 标记；客户端必须按纯文本渲染（不得把片段当 HTML 注入）
- `matchMode` ∈ `fts5` | `keyword`；`score` 越大越相关，两套排序规则见 11.4
- `errorCode`/`errorMessage` 只在 `status=failed` 时有值，`errorMessage` 已脱敏且不含文件绝对路径

### 11.2 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/documents` | 列表。查询参数 `page`（默认 1）、`size`（默认 20，最大 100）、`q`（标题模糊）、`status`（`imported`/`indexed`/`failed`）、`sha256`（按文件哈希过滤，用于重复导入后的选择）。返回分页包络 + `{ items: [DocumentSummary] }` |
| POST | `/api/v1/documents/import` | 导入文本资料：`{ "sourcePath", "sha256", "title"?, "onDuplicate"?: "reject"\|"reuse"\|"new" }`。服务端重算 SHA-256 与入参比对（防 TOCTOU），随后按 §9.1 落盘 → 短事务登记 → 分块 → 建索引；返回 `DocumentSummary` |
| GET | `/api/v1/documents/{id}` | `DocumentSummary`；不存在返回 `RESOURCE_NOT_FOUND` |
| GET | `/api/v1/documents/{id}/chunks` | 分块预览（供校对与引用核对）：分页 + `{ items: [ChunkView] }`；分块按 `chunkIndex` **升序**分页（客户端据此把命中的 `chunkIndex` 定位到所在页） |
| DELETE | `/api/v1/documents/{id}` | 删除资料及其分块与索引行；同 `sha256` 的文件已无其它资料引用时一并删除（补偿）。返回 `{ "deleted": true, "fileRemoved": boolean }` |
| POST | `/api/v1/documents/reindex` | 重建索引（版本化）：建立新 `indexVersion` → 全量分块重建 → 完整性校验 → 切换 `activeVersion`；任一步失败保留旧版本并返回 `DOCUMENT_INDEX_FAILED`（失败明细在 `error.message`；`failures` 只在成功响应里出现且恒为空数组）。返回 `IndexState` + `{ "chunks", "failures": [] }` |
| GET | `/api/v1/search/index` | `IndexState` |
| GET | `/api/v1/search` | 检索。查询参数 `q`（必填，1-200 字符）、`limit`（默认 10，最大 50）、`documentId`（可选）。返回 `{ "query", "mode", "tookMs", "total", "results": [SearchHit] }`；`total` 是**本次返回的命中条数**（检索不分页，不提供全量命中计数），需要更多结果就调大 `limit` |
| GET | `/api/v1/system/files` | 文件一致性报告：`{ "orphanFiles": [], "missingFiles": [], "tempFiles", "retentionHours" }` |
| POST | `/api/v1/system/files/cleanup` | 一致性补偿：`{ "dryRun": boolean }`（默认 true 只报告）。删除超过保留窗口的孤儿文件与临时文件，并把「数据库有记录但文件缺失」的资料标记为 `failed`。返回 `{ "removedFiles", "markedFailed", "dryRun" }` |

重复 `sha256` 且 `onDuplicate="reject"`（默认）时返回 `409 STATE_CONFLICT`，
`error.details` 为 `[{ "field": "sourcePath", "message": "..." }]`（§0.2 的 details 形状固定，不放结构化对象）；
客户端拿 `selectImportFile()` 已经算好的 `sha256` 调 `GET /api/v1/documents?sha256=<hash>` 取回**类型化**的
已存在资料列表，再让用户选择「复用 / 新建 / 取消」（开发文档 §9.1）：
复用 → 直接用列表里的 `id`；新建 → 带 `onDuplicate="new"` 重新调用；取消 → 不再请求。

### 11.3 检索增强的 AI 解释（引用来源）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/ai/questions/{id}/explain` | 请求体 `{ "userAnswer"?, "profileId"?, "useKnowledgeBase"?: boolean, "knowledgeLimit"?: number(1-10，默认 5), "query"?: string }`。`useKnowledgeBase=true` 时先用 §11.2 的检索取前 N 条分块作为 `references` 交给 Python（§7），响应在原有字段上增加 `citations` 与 `injectionSuspected` |

`citations` = `[{ "refId", "documentId", "chunkId", "title", "pageNo", "snippet" }]`：

1. `references` 传给 Python 时的元素为 `{ "refId": "K1", "title", "pageNo", "content" }`，`content` 是**不可信输入**；
2. 模型只能引用本次实际提供的 `refId`：Python 侧按提供集合过滤一次，Java 侧再按 `chunkId` 存在性复核一次，
   **绝不直接信任模型给出的引用**——保证「检索结果可追溯来源」，每个引用都能回到
   `GET /api/v1/documents/{id}/chunks` 逐字核对；
3. `injectionSuspected=true` 表示检索片段中命中了可疑指令模式，仅作标记与日志（脱敏），不改变主流程，
   也不影响 `citations` 的返回；
4. 对外 `citations[].snippet` 截断到 200 字符；Python 返回的 `used` 标记只用于过滤、不作为对外字段
   （Java 回映射时消费掉），因此客户端拿到的每条引用都是「模型确实引用过 + 能落到真实分块」的。

### 11.4 排序与降级规则（可解释）

1. **FTS5 路径**：`document_fts` 使用 `trigram` 分词器（对中文按三元组子串匹配，无需外部分词器）；
   查询按空白切分成词后**逐词加引号**做短语匹配并用 `AND` 连接（引号与 FTS5 运算符在拼接前一律转义），
   `bm25()` 越小越相关，返回 `score = -bm25`，`matchMode = "fts5"`。
2. **降级路径**：查询有效字符数不足 3（trigram 无法命中）、或 FTS5 不可用、或 FTS5 命中为空时，
   改用 `LIKE` 关键词检索（通配符已转义），按「首次命中位置 → 分块序号」排序，
   `score` 由命中位置与词频确定性计算，`matchMode = "keyword"`。
3. 两条路径都以 `document_id, chunk_index` 兜底排序，保证同一查询结果稳定可复现。
4. 检索只读取 `activeVersion` 的分块；重建期间旧版本继续对外服务。

### 11.5 导入与文件一致性（开发文档 §9.1）

1. 文件先写入 `<userData>/temp/`，计算 SHA-256，并校验后缀白名单（首期 `.txt` / `.md` / `.markdown`）、体积上限与可读性。
2. 原子移动到 `<userData>/files/imports/<fileId>/<originalName>`；`fileId` 由应用生成，原始文件名只作显示元数据
   （相对路径不得逃逸用户数据目录）。
3. 再在 SQLite **短事务**中登记 `imported_file` 与 `document`/`document_chunk`；事务失败则删除已落盘文件（补偿）。
4. 同一 `sha256` 只保留一份磁盘文件（`imported_file` 唯一），可被多条 `document` 记录引用。
5. 一致性巡检按保留窗口清理孤儿文件与临时文件；数据库有记录但文件缺失时把资料标记为 `failed` 并保留详情，
   不自动发布部分解析结果。

**两种失败形态要区分**（客户端不要一律按 400 处理）：

| 失败发生在 | HTTP | 表现 |
| --- | --- | --- |
| 校验/暂存/落盘之前（后缀不支持、超过上限、文件不可读、`sha256` 不匹配） | 400 | `DOCUMENT_FORMAT_UNSUPPORTED` / `DOCUMENT_TOO_LARGE` / `DOCUMENT_PARSE_FAILED` / `VALIDATION_ERROR`，**不产生任何资料记录** |
| 落盘之后的内容解析（例如文本既不是 UTF-8 也不是 GB18030） | **200** | 返回一条 `status=failed` 的资料记录（`errorCode=DOCUMENT_PARSE_FAILED`、`indexVersion=null`），**原文文件保留**、可重试导入（开发文档 §505） |

> 首期只支持 `.txt` / `.md`：PDF/DOCX 文本提取、OCR 与逐题校对属阶段 7（开发文档 §9.2、阶段表 §13）。
> 白名单之外的格式返回 `DOCUMENT_FORMAT_UNSUPPORTED`，页面提示支持的格式而不重试。
