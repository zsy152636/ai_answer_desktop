-- V2: 资料库与本地检索（阶段 6）——普通表部分
-- 约定（与 V1 一致）：
--   * 时间列存 UTC ISO-8601 文本；
--   * 枚举用 CHECK 约束，布尔用 INTEGER CHECK (x IN (0,1))；
--   * 资料原文与分块元数据存普通表，FTS5 只保存可检索文本与 chunk_id（开发文档 §10.1）；
--   * 被资料引用的文件记录一律 ON DELETE RESTRICT，禁止物理级联删除文件元数据。
--
-- 字段口径（开发文档 §523 与 §10.2 两处并列，这里取并集并消歧）：
--   * 分块同时能追溯到「资料」与「来源文件」：document_id 为权威外键，source_file_id 由
--     document.file_id 推导，因此不再冗余存储（避免双权威来源）；
--   * 保留 §523 明确要求的 parser_version 与 created_at。

CREATE TABLE IF NOT EXISTS imported_file (
  id            TEXT PRIMARY KEY,
  sha256        TEXT NOT NULL,
  size_bytes    INTEGER NOT NULL CHECK (size_bytes >= 0),
  mime_type     TEXT NOT NULL,
  original_name TEXT NOT NULL,
  relative_path TEXT NOT NULL,
  uploaded_at   TEXT NOT NULL,
  parse_status  TEXT NOT NULL CHECK (parse_status IN ('imported','parsed','failed')),
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);

-- 同一份文件（sha256 相同）在磁盘上只保留一份
CREATE UNIQUE INDEX IF NOT EXISTS uq_imported_file_sha ON imported_file(sha256);

CREATE TABLE IF NOT EXISTS document (
  id            TEXT PRIMARY KEY,
  title         TEXT NOT NULL,
  file_id       TEXT NOT NULL REFERENCES imported_file(id) ON DELETE RESTRICT,
  status        TEXT NOT NULL CHECK (status IN ('imported','indexed','failed')),
  source_kind   TEXT NOT NULL CHECK (source_kind IN ('text','markdown')),
  chunk_count   INTEGER NOT NULL DEFAULT 0 CHECK (chunk_count >= 0),
  char_count    INTEGER NOT NULL DEFAULT 0 CHECK (char_count >= 0),
  index_version INTEGER,
  parser_version TEXT,
  error_code    TEXT,
  error_message TEXT,
  created_at    TEXT NOT NULL,
  updated_at    TEXT NOT NULL
);

-- 可追溯分块：检索结果必须能回到具体分块（阶段 6 完成定义）
CREATE TABLE IF NOT EXISTS document_chunk (
  id             TEXT PRIMARY KEY,
  document_id    TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  page_no        INTEGER,
  chunk_index    INTEGER NOT NULL CHECK (chunk_index >= 0),
  content        TEXT NOT NULL,
  content_hash   TEXT NOT NULL,
  parser_version TEXT NOT NULL,
  index_version  INTEGER NOT NULL CHECK (index_version >= 1),
  created_at     TEXT NOT NULL
);

-- 索引版本切换状态（开发文档 §524：新版本构建通过后才切换 active_version）
CREATE TABLE IF NOT EXISTS index_state (
  index_name       TEXT PRIMARY KEY,
  active_version   INTEGER NOT NULL CHECK (active_version >= 1),
  building_version INTEGER,
  updated_at       TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_document_status  ON document(status, updated_at);
CREATE INDEX IF NOT EXISTS idx_document_file    ON document(file_id);
CREATE INDEX IF NOT EXISTS idx_chunk_document   ON document_chunk(document_id, index_version, chunk_index);
CREATE UNIQUE INDEX IF NOT EXISTS uq_chunk_version ON document_chunk(document_id, index_version, chunk_index);
CREATE INDEX IF NOT EXISTS idx_chunk_version    ON document_chunk(index_version);
