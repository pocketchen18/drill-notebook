CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS question_bank (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    description TEXT,
    source_type TEXT,
    created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS question (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bank_id INTEGER NOT NULL REFERENCES question_bank(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    stem TEXT NOT NULL,
    options TEXT,
    answer TEXT NOT NULL,
    analysis TEXT,
    difficulty INTEGER DEFAULT 3,
    tags TEXT,
    chapter TEXT,
    group_id TEXT,
    order_in_group INTEGER,
    content_hash TEXT,
    created_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_question_bank_id ON question(bank_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_question_bank_hash ON question(bank_id, content_hash);

CREATE TABLE IF NOT EXISTS knowledge_point (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bank_id INTEGER REFERENCES question_bank(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    category TEXT,
    tags TEXT,
    heading_path TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_knowledge_point_bank ON knowledge_point(bank_id, category, id);

CREATE TABLE IF NOT EXISTS knowledge_point_question (
    knowledge_point_id INTEGER NOT NULL REFERENCES knowledge_point(id) ON DELETE CASCADE,
    question_id INTEGER NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    PRIMARY KEY (knowledge_point_id, question_id)
);

CREATE TABLE IF NOT EXISTS knowledge_point_original (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    point_id  INTEGER NOT NULL REFERENCES knowledge_point(id) ON DELETE CASCADE,
    role      TEXT    NOT NULL CHECK (role IN ('original', 'summary')),
    content   TEXT    NOT NULL,
    saved_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    UNIQUE (point_id, role)
);

CREATE INDEX IF NOT EXISTS idx_kp_original_point ON knowledge_point_original(point_id);

CREATE TABLE IF NOT EXISTS answer_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    question_id INTEGER NOT NULL REFERENCES question(id),
    user_answer TEXT,
    is_correct INTEGER,
    time_spent INTEGER,
    session_id TEXT,
    grading_status TEXT,
    grading_json TEXT,
    answered_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_answer_question ON answer_record(question_id, id DESC);

CREATE TABLE IF NOT EXISTS notebook (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS note_page (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    notebook_id INTEGER NOT NULL REFERENCES notebook(id) ON DELETE CASCADE,
    parent_id INTEGER,
    title TEXT,
    sort_order INTEGER DEFAULT 0,
    content TEXT,
    content_hash TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_note_page_notebook ON note_page(notebook_id, sort_order, id);

CREATE TABLE IF NOT EXISTS note_question_ref (
    note_id INTEGER NOT NULL,
    question_id INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    added_at TEXT DEFAULT (datetime('now')),
    PRIMARY KEY (note_id, question_id)
);

CREATE TABLE IF NOT EXISTS ai_config (
    purpose TEXT PRIMARY KEY,
    provider TEXT,
    endpoint TEXT,
    model TEXT,
    encrypted_key TEXT,
    key_meta TEXT,
    params TEXT
);

CREATE TABLE IF NOT EXISTS ai_chat_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    title TEXT NOT NULL,
    archived INTEGER NOT NULL DEFAULT 0,
    model TEXT,
    tags TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER REFERENCES ai_chat_session(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    content_cipher TEXT,
    content_meta TEXT,
    created_at TEXT DEFAULT (datetime('now'))
);

CREATE VIRTUAL TABLE IF NOT EXISTS question_fts USING fts5(
    stem, answer, analysis, tags,
    content='question', content_rowid='id'
);

CREATE TABLE IF NOT EXISTS study_plan_group (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_date TEXT NOT NULL,
    title TEXT NOT NULL,
    note TEXT,
    source TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS study_plan_item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    group_id INTEGER NOT NULL REFERENCES study_plan_group(id) ON DELETE CASCADE,
    plan_date TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    note TEXT,
    status TEXT NOT NULL DEFAULT 'todo',
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    completed_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_study_plan_item_date_status ON study_plan_item(plan_date, status);
CREATE INDEX IF NOT EXISTS idx_study_plan_item_group ON study_plan_item(group_id);
CREATE INDEX IF NOT EXISTS idx_study_plan_item_resource ON study_plan_item(resource_type, resource_id);
CREATE INDEX IF NOT EXISTS idx_study_plan_group_date ON study_plan_group(plan_date, id);

CREATE TABLE IF NOT EXISTS spaced_repetition_config (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              TEXT NOT NULL UNIQUE,
    is_default        INTEGER NOT NULL DEFAULT 0,
    intervals_json    TEXT NOT NULL DEFAULT '{"1":1,"2":6,"3":16,"4":36,"5":70}',
    initial_ef        REAL NOT NULL DEFAULT 2.5,
    minimum_ef        REAL NOT NULL DEFAULT 1.3,
    max_interval_days INTEGER NOT NULL DEFAULT 365,
    wrong_strategy    TEXT NOT NULL DEFAULT 'reduce_half',
    wrong_fixed_days  REAL NOT NULL DEFAULT 1.0,
    daily_new_limit   INTEGER NOT NULL DEFAULT 20,
    daily_review_limit INTEGER NOT NULL DEFAULT 100,
    priority_mode     TEXT NOT NULL DEFAULT 'due_first',
    created_at        TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS review_schedule (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    item_type       TEXT NOT NULL,
    item_id         INTEGER NOT NULL,
    config_id       INTEGER REFERENCES spaced_repetition_config(id),
    ef              REAL NOT NULL DEFAULT 2.5,
    interval        REAL NOT NULL DEFAULT 0,
    repetitions     INTEGER NOT NULL DEFAULT 0,
    next_review     TEXT,
    last_review     TEXT,
    last_quality    INTEGER,
    total_reviews   INTEGER NOT NULL DEFAULT 0,
    total_wrong     INTEGER NOT NULL DEFAULT 0,
    streak_correct  INTEGER NOT NULL DEFAULT 0,
    status          TEXT NOT NULL DEFAULT 'new',
    created_at      TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at      TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(item_type, item_id, config_id)
);

CREATE INDEX IF NOT EXISTS idx_review_schedule_due
    ON review_schedule(item_type, next_review);
CREATE INDEX IF NOT EXISTS idx_review_schedule_item
    ON review_schedule(item_type, item_id);

CREATE TABLE IF NOT EXISTS review_log (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    schedule_id         INTEGER NOT NULL REFERENCES review_schedule(id) ON DELETE CASCADE,
    quality             INTEGER NOT NULL,
    response_time       INTEGER,
    scheduled_interval  REAL,
    actual_interval     REAL,
    source              TEXT NOT NULL DEFAULT 'manual',
    reviewed_at         TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_review_log_schedule
    ON review_log(schedule_id, reviewed_at DESC);

INSERT OR IGNORE INTO spaced_repetition_config(name, is_default, intervals_json,
    initial_ef, minimum_ef, max_interval_days,
    wrong_strategy, wrong_fixed_days,
    daily_new_limit, daily_review_limit, priority_mode)
VALUES ('标准模式', 1,
    '{"1":1,"2":6,"3":16,"4":36,"5":70}',
    2.5, 1.3, 365,
    'reduce_half', 1.0,
    20, 100, 'due_first');

INSERT OR IGNORE INTO spaced_repetition_config(name, is_default, intervals_json,
    initial_ef, minimum_ef, max_interval_days,
    wrong_strategy, wrong_fixed_days,
    daily_new_limit, daily_review_limit, priority_mode)
VALUES ('考前突击', 0,
    '{"1":0.5,"2":1,"3":2,"4":4,"5":7,"6":14}',
    2.0, 1.3, 30,
    'reset', 0.5,
    50, 200, 'worst_first');

INSERT OR IGNORE INTO spaced_repetition_config(name, is_default, intervals_json,
    initial_ef, minimum_ef, max_interval_days,
    wrong_strategy, wrong_fixed_days,
    daily_new_limit, daily_review_limit, priority_mode)
VALUES ('保守学习', 0,
    '{"1":1,"2":3,"3":7,"4":14,"5":30,"6":60,"7":120}',
    2.5, 1.3, 365,
    'reset', 1.0,
    10, 50, 'due_first');

-- ============================================================
-- v8: Notebook retrieval schema (BM25 + Embedding)
-- ============================================================

CREATE TABLE IF NOT EXISTS retrieval_chunk (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    corpus_type TEXT NOT NULL DEFAULT 'NOTEBOOK',
    corpus_id INTEGER NOT NULL,
    source_id INTEGER NOT NULL,
    chunk_index INTEGER NOT NULL,
    title TEXT,
    heading_path TEXT,
    text TEXT NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    UNIQUE(corpus_type, source_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_retrieval_chunk_source ON retrieval_chunk(corpus_type, source_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_chunk_corpus ON retrieval_chunk(corpus_type, corpus_id, source_id);

CREATE VIRTUAL TABLE IF NOT EXISTS retrieval_chunk_fts USING fts5(
    title, heading_path, text,
    tokenize='trigram'
);

CREATE TABLE IF NOT EXISTS embedding_model (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    catalog_id TEXT NOT NULL UNIQUE,
    provider_model_id TEXT NOT NULL,
    artifact_revision TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    installation_state TEXT NOT NULL DEFAULT 'AVAILABLE'
        CHECK (installation_state IN ('AVAILABLE','DOWNLOADING','VERIFYING','READY','UNINSTALLING','FAILED','PAUSED')),
    manifest_json TEXT,
    download_progress_json TEXT,
    download_error TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS embedding_space (
    embedding_space_id TEXT PRIMARY KEY,
    canonical_contract_json TEXT NOT NULL,
    provider_type TEXT NOT NULL,
    model_identifier TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    state TEXT NOT NULL DEFAULT 'DISABLED'
        CHECK (state IN ('DISABLED','REBUILDING','ACTIVE','ERROR','UNINSTALLING')),
    coverage REAL NOT NULL DEFAULT 0.0 CHECK (coverage >= 0.0 AND coverage <= 1.0),
    is_selected INTEGER NOT NULL DEFAULT 0 CHECK (is_selected IN (0, 1)),
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_embedding_space_selected
    ON embedding_space(is_selected) WHERE is_selected = 1;

CREATE TABLE IF NOT EXISTS retrieval_embedding (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chunk_id INTEGER NOT NULL REFERENCES retrieval_chunk(id) ON DELETE CASCADE,
    corpus_type TEXT NOT NULL,
    embedding_space_id TEXT NOT NULL REFERENCES embedding_space(embedding_space_id),
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    content_hash TEXT NOT NULL,
    vector_blob BLOB NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    UNIQUE(chunk_id, embedding_space_id)
);

CREATE INDEX IF NOT EXISTS idx_retrieval_embedding_space
    ON retrieval_embedding(embedding_space_id, corpus_type);

-- Dimension-enforcement trigger is created in DatabaseInitializer.java
-- as a standalone exec() call to avoid ;-splitting issues.

CREATE TABLE IF NOT EXISTS embedding_job (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    corpus_type TEXT NOT NULL,
    source_id INTEGER NOT NULL,
    source_content_hash TEXT NOT NULL,
    embedding_space_id TEXT NOT NULL REFERENCES embedding_space(embedding_space_id) ON DELETE CASCADE,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED','CLAIMED','RETRY','COMPLETED','SUPERSEDED','FAILED')),
    claim_token TEXT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_run_at TEXT,
    error TEXT,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    UNIQUE(corpus_type, source_id, source_content_hash, embedding_space_id)
);

CREATE INDEX IF NOT EXISTS idx_embedding_job_status
    ON embedding_job(status, next_run_at);
CREATE INDEX IF NOT EXISTS idx_embedding_job_space
    ON embedding_job(embedding_space_id);

CREATE TABLE IF NOT EXISTS note_attachment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    page_id INTEGER NOT NULL REFERENCES note_page(id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    file_size INTEGER NOT NULL,
    sha256 TEXT,
    created_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_attachment_page ON note_attachment(page_id);
