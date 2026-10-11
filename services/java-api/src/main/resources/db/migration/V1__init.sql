-- V1: 全量基线 DDL（AI 智能答题系统 v1）
-- 约定：
--   * 所有时间列存 UTC ISO-8601 文本（由 Java 侧统一写入，避免 SQLite 本地时区歧义）
--   * 题目选项采用规范化表 question_option（不使用 options_json，避免双权威来源）
--   * 被试卷/作答/错题引用的题目版本一律 ON DELETE RESTRICT，禁止物理级联删除
--   * 外键依赖 PRAGMA foreign_keys=ON（由连接 URL 参数保证）

CREATE TABLE IF NOT EXISTS schema_version (
  version     INTEGER PRIMARY KEY,
  description TEXT    NOT NULL DEFAULT '',
  applied_at  TEXT    NOT NULL,
  checksum    TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS app_meta (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

-- ---------------------------------------------------------------- 题库与版本
CREATE TABLE IF NOT EXISTS question (
  id                 TEXT PRIMARY KEY,
  title              TEXT NOT NULL,
  question_type      TEXT NOT NULL CHECK (question_type IN
                       ('single_choice','multiple_choice','true_false','fill_blank','short_answer','essay')),
  status             TEXT NOT NULL CHECK (status IN ('draft','published','archived')),
  difficulty         INTEGER NOT NULL DEFAULT 2 CHECK (difficulty BETWEEN 1 AND 5),
  current_version_id TEXT,
  created_at         TEXT NOT NULL,
  updated_at         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS question_version (
  id                   TEXT PRIMARY KEY,
  question_id          TEXT NOT NULL REFERENCES question(id) ON DELETE RESTRICT,
  version_no           INTEGER NOT NULL CHECK (version_no > 0),
  stem                 TEXT NOT NULL,
  answer_json          TEXT NOT NULL,
  explanation          TEXT,
  rubric_json          TEXT,
  knowledge_tags_json  TEXT NOT NULL DEFAULT '[]',
  content_hash         TEXT NOT NULL,
  change_note          TEXT,
  created_at           TEXT NOT NULL,
  UNIQUE (question_id, version_no)
);

CREATE TABLE IF NOT EXISTS question_option (
  id                  TEXT PRIMARY KEY,
  question_version_id TEXT NOT NULL REFERENCES question_version(id) ON DELETE CASCADE,
  option_key          TEXT NOT NULL,
  content             TEXT NOT NULL,
  is_correct          INTEGER NOT NULL DEFAULT 0 CHECK (is_correct IN (0,1)),
  order_no            INTEGER NOT NULL,
  UNIQUE (question_version_id, option_key)
);

-- ---------------------------------------------------------------- 试卷与快照
CREATE TABLE IF NOT EXISTS paper (
  id                  TEXT PRIMARY KEY,
  title               TEXT NOT NULL,
  description         TEXT,
  status              TEXT NOT NULL CHECK (status IN ('draft','published','archived')),
  current_snapshot_id TEXT,
  published_at        TEXT,
  created_at          TEXT NOT NULL,
  updated_at          TEXT NOT NULL
);

-- 草稿态试卷组成（可编辑）
CREATE TABLE IF NOT EXISTS paper_question (
  id                  TEXT PRIMARY KEY,
  paper_id            TEXT NOT NULL REFERENCES paper(id) ON DELETE CASCADE,
  question_id         TEXT NOT NULL REFERENCES question(id) ON DELETE RESTRICT,
  question_version_id TEXT NOT NULL REFERENCES question_version(id) ON DELETE RESTRICT,
  order_no            INTEGER NOT NULL CHECK (order_no > 0),
  score               REAL    NOT NULL CHECK (score > 0),
  UNIQUE (paper_id, order_no)
);

-- 已发布试卷的不可变快照。
-- kind='practice' 表示「错题练习」生成的临时快照：paper_id 为 NULL，不进入试卷列表。
CREATE TABLE IF NOT EXISTS paper_snapshot (
  id             TEXT PRIMARY KEY,
  paper_id       TEXT REFERENCES paper(id) ON DELETE RESTRICT,
  kind           TEXT NOT NULL DEFAULT 'paper' CHECK (kind IN ('paper','practice')),
  version_no     INTEGER NOT NULL CHECK (version_no > 0),
  title          TEXT NOT NULL,
  description    TEXT,
  question_count INTEGER NOT NULL CHECK (question_count > 0),
  total_score    REAL    NOT NULL CHECK (total_score > 0),
  published_at   TEXT NOT NULL,
  created_at     TEXT NOT NULL,
  UNIQUE (paper_id, version_no)
);

CREATE TABLE IF NOT EXISTS paper_snapshot_question (
  id                  TEXT PRIMARY KEY,
  snapshot_id         TEXT NOT NULL REFERENCES paper_snapshot(id) ON DELETE RESTRICT,
  question_id         TEXT NOT NULL REFERENCES question(id) ON DELETE RESTRICT,
  question_version_id TEXT NOT NULL REFERENCES question_version(id) ON DELETE RESTRICT,
  order_no            INTEGER NOT NULL CHECK (order_no > 0),
  score               REAL    NOT NULL CHECK (score > 0),
  snapshot_json       TEXT    NOT NULL,
  UNIQUE (snapshot_id, order_no)
);

-- ---------------------------------------------------------------- 作答与判分
CREATE TABLE IF NOT EXISTS attempt (
  id               TEXT PRIMARY KEY,
  paper_id         TEXT REFERENCES paper(id) ON DELETE RESTRICT,
  paper_snapshot_id TEXT REFERENCES paper_snapshot(id) ON DELETE RESTRICT,
  mode             TEXT NOT NULL CHECK (mode IN ('paper','wrong_practice')),
  status           TEXT NOT NULL CHECK (status IN ('in_progress','submitted','graded','abandoned')),
  question_count   INTEGER NOT NULL DEFAULT 0,
  total_score      REAL,
  max_score        REAL,
  started_at       TEXT NOT NULL,
  submitted_at     TEXT,
  graded_at        TEXT,
  abandoned_at     TEXT,
  duration_seconds INTEGER
);

CREATE TABLE IF NOT EXISTS attempt_answer (
  id                    TEXT PRIMARY KEY,
  attempt_id            TEXT NOT NULL REFERENCES attempt(id) ON DELETE CASCADE,
  snapshot_question_id  TEXT NOT NULL REFERENCES paper_snapshot_question(id) ON DELETE RESTRICT,
  question_id           TEXT NOT NULL REFERENCES question(id) ON DELETE RESTRICT,
  question_version_id   TEXT NOT NULL REFERENCES question_version(id) ON DELETE RESTRICT,
  answer_json           TEXT NOT NULL,
  score                 REAL,
  max_score             REAL NOT NULL CHECK (max_score >= 0),
  grading_method        TEXT NOT NULL DEFAULT 'ungraded' CHECK (grading_method IN
                          ('exact','normalized','partial_credit','ai_rubric','manual','ungraded')),
  grading_status        TEXT NOT NULL DEFAULT 'pending_review' CHECK (grading_status IN
                          ('graded','pending_review','failed')),
  grading_detail_json   TEXT,
  saved_at              TEXT NOT NULL,
  UNIQUE (attempt_id, snapshot_question_id)
);

-- ---------------------------------------------------------------- 错题闭环
CREATE TABLE IF NOT EXISTS wrong_question (
  id                  TEXT PRIMARY KEY,
  question_id         TEXT NOT NULL REFERENCES question(id) ON DELETE RESTRICT,
  question_version_id TEXT NOT NULL REFERENCES question_version(id) ON DELETE RESTRICT,
  status              TEXT NOT NULL CHECK (status IN ('active','reviewing','mastered','archived')),
  wrong_count         INTEGER NOT NULL DEFAULT 1 CHECK (wrong_count >= 1),
  correct_streak      INTEGER NOT NULL DEFAULT 0 CHECK (correct_streak >= 0),
  last_score          REAL,
  max_score           REAL,
  last_wrong_at       TEXT NOT NULL,
  next_review_at      TEXT,
  created_at          TEXT NOT NULL,
  updated_at          TEXT NOT NULL,
  UNIQUE (question_version_id)
);

CREATE TABLE IF NOT EXISTS wrong_question_review (
  id                    TEXT PRIMARY KEY,
  wrong_question_id     TEXT NOT NULL REFERENCES wrong_question(id) ON DELETE CASCADE,
  attempt_answer_id     TEXT REFERENCES attempt_answer(id) ON DELETE SET NULL,
  result                TEXT NOT NULL CHECK (result IN ('correct','wrong')),
  note                  TEXT,
  interval_days_before  INTEGER,
  interval_days_after   INTEGER,
  next_review_at        TEXT,
  duration_seconds      INTEGER,
  reviewed_at           TEXT NOT NULL
);

-- ---------------------------------------------------------------- 异步任务
CREATE TABLE IF NOT EXISTS async_task (
  id                 TEXT PRIMARY KEY,
  task_type          TEXT NOT NULL,
  status             TEXT NOT NULL CHECK (status IN
                       ('queued','running','succeeded','retry_wait','failed','cancelled')),
  payload_json       TEXT NOT NULL,
  result_json        TEXT,
  idempotency_key    TEXT NOT NULL UNIQUE,
  attempt_count      INTEGER NOT NULL DEFAULT 0,
  max_attempts       INTEGER NOT NULL DEFAULT 5,
  lease_owner        TEXT,
  lease_until        TEXT,
  heartbeat_at       TEXT,
  next_run_at        TEXT,
  last_error_code    TEXT,
  last_error_message TEXT,
  created_at         TEXT NOT NULL,
  updated_at         TEXT NOT NULL
);

-- ---------------------------------------------------------------- LLM 配置与凭据
CREATE TABLE IF NOT EXISTS llm_profile (
  id                       TEXT PRIMARY KEY,
  name                     TEXT NOT NULL,
  provider                 TEXT NOT NULL CHECK (provider IN
                             ('openai-compatible','ollama','anthropic','azure-openai')),
  base_url                 TEXT NOT NULL,
  model                    TEXT NOT NULL,
  api_key_ref              TEXT,
  timeout_seconds          INTEGER NOT NULL DEFAULT 60 CHECK (timeout_seconds > 0),
  connect_timeout_seconds  INTEGER NOT NULL DEFAULT 5 CHECK (connect_timeout_seconds > 0),
  allow_local_network      INTEGER NOT NULL DEFAULT 0 CHECK (allow_local_network IN (0,1)),
  parameters_json          TEXT NOT NULL DEFAULT '{}',
  credential_configured    INTEGER NOT NULL DEFAULT 0 CHECK (credential_configured IN (0,1)),
  is_default               INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0,1)),
  created_at               TEXT NOT NULL,
  updated_at               TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS llm_config_state (
  id                 INTEGER PRIMARY KEY CHECK (id = 1),
  default_profile_id TEXT,
  config_revision    INTEGER NOT NULL DEFAULT 0,
  updated_at         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS llm_import_preview (
  id           TEXT PRIMARY KEY,
  payload_json TEXT NOT NULL,
  summary_json TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  expires_at   TEXT NOT NULL,
  applied      INTEGER NOT NULL DEFAULT 0 CHECK (applied IN (0,1))
);

-- ---------------------------------------------------------------- 横向支撑
CREATE TABLE IF NOT EXISTS idempotency_record (
  key           TEXT PRIMARY KEY,
  scope         TEXT NOT NULL,
  request_hash  TEXT NOT NULL,
  status_code   INTEGER NOT NULL,
  response_json TEXT NOT NULL,
  created_at    TEXT NOT NULL,
  expires_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_event (
  id          TEXT PRIMARY KEY,
  event_type  TEXT NOT NULL,
  entity_type TEXT,
  entity_id   TEXT,
  detail_json TEXT,
  created_at  TEXT NOT NULL
);

-- ---------------------------------------------------------------- 索引
CREATE INDEX IF NOT EXISTS idx_task_poll          ON async_task(status, next_run_at, created_at);
CREATE INDEX IF NOT EXISTS idx_wrong_review       ON wrong_question(status, next_review_at);
CREATE INDEX IF NOT EXISTS idx_attempt_started    ON attempt(started_at);
CREATE INDEX IF NOT EXISTS idx_question_status    ON question(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_question_type      ON question(question_type);
CREATE INDEX IF NOT EXISTS idx_qversion_question  ON question_version(question_id, version_no);
CREATE INDEX IF NOT EXISTS idx_option_version     ON question_option(question_version_id, order_no);
CREATE INDEX IF NOT EXISTS idx_paper_status       ON paper(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_paper_question     ON paper_question(paper_id, order_no);
CREATE INDEX IF NOT EXISTS idx_snapshot_paper     ON paper_snapshot(paper_id, version_no);
CREATE INDEX IF NOT EXISTS idx_snapq_snapshot     ON paper_snapshot_question(snapshot_id, order_no);
CREATE INDEX IF NOT EXISTS idx_answer_attempt     ON attempt_answer(attempt_id);
CREATE INDEX IF NOT EXISTS idx_attempt_paper      ON attempt(paper_id, started_at);
CREATE INDEX IF NOT EXISTS idx_wq_updated         ON wrong_question(updated_at);
CREATE INDEX IF NOT EXISTS idx_review_wq          ON wrong_question_review(wrong_question_id, reviewed_at);
CREATE INDEX IF NOT EXISTS idx_idem_expires       ON idempotency_record(expires_at);
CREATE INDEX IF NOT EXISTS idx_audit_created      ON audit_event(created_at);
