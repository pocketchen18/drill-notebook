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
    sort_index INTEGER,
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
    reasoning_cipher TEXT,
    reasoning_meta TEXT,
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

-- ============================================================
-- v9: AI 跨会话记忆（schema9）
--
-- 时间列一律无 SQL 默认值：由服务端写入 ISO-8601 UTC 且固定毫秒精度
-- （uuuu-MM-dd'T'HH:mm:ss.SSS'Z'），保证字典序 == 时间序。旧表的
-- datetime('now') 值只与同格式列比较，不跨格式排序。
-- 逻辑容量账本（ai_chat_storage）计的是落库字节，不是物理磁盘上限。
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_memory_vault (
    id TEXT PRIMARY KEY,
    singleton INTEGER NOT NULL DEFAULT 1 UNIQUE CHECK (singleton = 1),
    wrapped_dek TEXT NOT NULL,
    wrap_meta TEXT NOT NULL,
    crypto_version INTEGER NOT NULL DEFAULT 1,
    mode TEXT NOT NULL CHECK (mode IN ('fingerprint', 'password')),
    epoch INTEGER NOT NULL DEFAULT 1,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_memory_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision INTEGER NOT NULL DEFAULT 1,
    enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    auto_extract INTEGER NOT NULL DEFAULT 0 CHECK (auto_extract IN (0, 1)),
    compress_session INTEGER NOT NULL DEFAULT 0 CHECK (compress_session IN (0, 1)),
    auto_apply_low_risk INTEGER NOT NULL DEFAULT 1 CHECK (auto_apply_low_risk IN (0, 1)),
    policy_json TEXT NOT NULL,
    use_consent_fingerprint TEXT,
    maintenance_consent_fingerprint TEXT,
    consented_at TEXT,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_chat_turn (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT NOT NULL UNIQUE,
    session_id INTEGER NOT NULL REFERENCES ai_chat_session(id) ON DELETE CASCADE,
    retry_of TEXT,
    status TEXT NOT NULL CHECK (status IN
        ('RUNNING', 'COMPLETED', 'ABORTED', 'TRUNCATED', 'FAILED', 'INTERRUPTED')),
    request_meta_cipher TEXT,
    request_meta_crypto TEXT,
    user_message_id INTEGER REFERENCES ai_chat_message(id) ON DELETE SET NULL,
    assistant_message_id INTEGER REFERENCES ai_chat_message(id) ON DELETE SET NULL,
    context_generation INTEGER NOT NULL,
    source_scope_type TEXT NOT NULL CHECK (source_scope_type IN ('GLOBAL', 'NOTEBOOK')),
    source_scope_id INTEGER NOT NULL,
    source_timezone TEXT NOT NULL,
    runtime_generation TEXT NOT NULL,
    memory_eligible INTEGER NOT NULL DEFAULT 0,
    memory_policy_revision INTEGER,
    source_revision INTEGER NOT NULL DEFAULT 1,
    stored_history_bytes INTEGER NOT NULL DEFAULT 0 CHECK (stored_history_bytes >= 0),
    reserved_history_bytes INTEGER NOT NULL DEFAULT 0 CHECK (reserved_history_bytes >= 0),
    content_deleted_at TEXT,
    finish_reason TEXT,
    persistence_status TEXT NOT NULL DEFAULT 'pending',
    result_meta_cipher TEXT,
    result_meta_crypto TEXT,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    CHECK (content_deleted_at IS NOT NULL OR
        (request_meta_cipher IS NOT NULL AND request_meta_crypto IS NOT NULL)),
    CHECK ((source_scope_type = 'GLOBAL' AND source_scope_id = 0) OR
        (source_scope_type = 'NOTEBOOK' AND source_scope_id > 0))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_turn_running_session
    ON ai_chat_turn(session_id) WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_ai_turn_session_id ON ai_chat_turn(session_id, id);

CREATE TABLE IF NOT EXISTS ai_chat_attachment (
    id TEXT PRIMARY KEY,
    turn_id INTEGER NOT NULL REFERENCES ai_chat_turn(id) ON DELETE CASCADE,
    block_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('TEXT', 'IMAGE')),
    payload_cipher BLOB NOT NULL,
    crypto_meta TEXT NOT NULL,
    byte_length INTEGER NOT NULL CHECK (byte_length =
        length(payload_cipher) + length(CAST(crypto_meta AS BLOB))),
    created_at TEXT NOT NULL,
    UNIQUE(turn_id, block_id)
);

CREATE TABLE IF NOT EXISTS ai_memory_item (
    row_id TEXT PRIMARY KEY,
    memory_id TEXT NOT NULL,
    vault_id TEXT NOT NULL REFERENCES ai_memory_vault(id),
    version INTEGER NOT NULL CHECK (version > 0),
    scope_type TEXT NOT NULL CHECK (scope_type IN ('GLOBAL', 'NOTEBOOK')),
    scope_id INTEGER NOT NULL DEFAULT 0,
    kind TEXT NOT NULL CHECK (kind IN ('PREFERENCE', 'GOAL', 'BACKGROUND', 'TASK')),
    predicate_key_hmac TEXT NOT NULL,
    family_key_hmac TEXT NOT NULL,
    value_hmac TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN
        ('ACTIVE', 'PENDING', 'SUPERSEDED', 'DISABLED', 'EXPIRED')),
    payload_cipher TEXT NOT NULL,
    crypto_meta TEXT NOT NULL,
    origin TEXT NOT NULL CHECK (origin IN ('AUTO', 'MANUAL')),
    manual_protected INTEGER NOT NULL DEFAULT 0,
    pinned INTEGER NOT NULL DEFAULT 0,
    valid_from TEXT,
    valid_until TEXT,
    observed_at TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    last_confirmed_at TEXT NOT NULL,
    UNIQUE(memory_id, version),
    CHECK ((scope_type = 'GLOBAL' AND scope_id = 0) OR
        (scope_type = 'NOTEBOOK' AND scope_id > 0))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_one_active_family
    ON ai_memory_item(vault_id, scope_type, scope_id, family_key_hmac) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_memory_scope_status
    ON ai_memory_item(vault_id, scope_type, scope_id, status);

CREATE TABLE IF NOT EXISTS ai_memory_evidence (
    id TEXT PRIMARY KEY,
    vault_id TEXT NOT NULL REFERENCES ai_memory_vault(id),
    session_id INTEGER REFERENCES ai_chat_session(id) ON DELETE CASCADE,
    turn_id INTEGER REFERENCES ai_chat_turn(id) ON DELETE SET NULL,
    message_id INTEGER REFERENCES ai_chat_message(id) ON DELETE SET NULL,
    source_block_id TEXT NOT NULL,
    source_hmac TEXT NOT NULL,
    quote_cipher TEXT NOT NULL,
    crypto_meta TEXT NOT NULL,
    source_created_at TEXT NOT NULL,
    UNIQUE(vault_id, source_hmac)
);

CREATE TABLE IF NOT EXISTS ai_memory_item_evidence (
    item_row_id TEXT NOT NULL REFERENCES ai_memory_item(row_id) ON DELETE CASCADE,
    evidence_id TEXT NOT NULL REFERENCES ai_memory_evidence(id) ON DELETE CASCADE,
    PRIMARY KEY (item_row_id, evidence_id)
);

CREATE TABLE IF NOT EXISTS ai_memory_proposal (
    id TEXT PRIMARY KEY,
    vault_id TEXT NOT NULL REFERENCES ai_memory_vault(id),
    session_id INTEGER NOT NULL REFERENCES ai_chat_session(id) ON DELETE CASCADE,
    source_turn_id INTEGER REFERENCES ai_chat_turn(id) ON DELETE SET NULL,
    evidence_id TEXT REFERENCES ai_memory_evidence(id) ON DELETE SET NULL,
    target_memory_id TEXT NOT NULL,
    target_version INTEGER NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('REPLACE_PROPOSAL', 'RETRACT_PROPOSAL')),
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'STALE')),
    version INTEGER NOT NULL DEFAULT 1,
    payload_cipher TEXT NOT NULL,
    crypto_meta TEXT NOT NULL,
    dedup_hmac TEXT NOT NULL,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    UNIQUE(vault_id, dedup_hmac)
);

CREATE TABLE IF NOT EXISTS ai_session_context (
    session_id INTEGER PRIMARY KEY REFERENCES ai_chat_session(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 2 CHECK (schema_version = 2),
    through_turn_id INTEGER NOT NULL,
    context_generation INTEGER NOT NULL,
    vault_epoch INTEGER NOT NULL,
    payload_cipher TEXT NOT NULL,
    crypto_meta TEXT NOT NULL,
    estimated_tokens INTEGER NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_memory_job (
    id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('EXTRACT', 'SUMMARY')),
    session_id INTEGER NOT NULL REFERENCES ai_chat_session(id) ON DELETE CASCADE,
    from_turn_id INTEGER NOT NULL,
    through_turn_id INTEGER NOT NULL,
    context_generation INTEGER NOT NULL,
    source_scope_type TEXT NOT NULL CHECK (source_scope_type IN ('GLOBAL', 'NOTEBOOK')),
    source_scope_id INTEGER NOT NULL,
    source_timezone TEXT NOT NULL,
    vault_epoch INTEGER NOT NULL,
    policy_revision INTEGER NOT NULL,
    target_fingerprint TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    input_revision TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'CLAIMED', 'RETRY', 'WAITING_UNLOCK',
        'WAITING_CONSENT', 'PAUSED_BUDGET', 'COMPLETED', 'SUPERSEDED', 'FAILED')),
    lease_token TEXT,
    lease_until TEXT,
    attempts INTEGER NOT NULL DEFAULT 0,
    outcome_code TEXT CHECK (outcome_code IS NULL OR outcome_code IN
        ('APPLIED', 'NO_NEW_INFORMATION', 'NO_VALID_CANDIDATES', 'NO_COMPRESSION_GAIN',
         'SOURCE_DELETED', 'SOURCE_TOO_LARGE')),
    next_run_at TEXT,
    error_code TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(kind, session_id, from_turn_id, through_turn_id, input_revision, policy_revision)
);

CREATE INDEX IF NOT EXISTS idx_memory_job_poll ON ai_memory_job(status, next_run_at);

CREATE TABLE IF NOT EXISTS ai_memory_suppression (
    vault_id TEXT NOT NULL REFERENCES ai_memory_vault(id),
    scope_type TEXT NOT NULL,
    scope_id INTEGER NOT NULL,
    suppression_kind TEXT NOT NULL CHECK (suppression_kind IN ('FAMILY', 'PREDICATE', 'EVIDENCE')),
    key_hmac TEXT NOT NULL,
    source_through_turn_id INTEGER,
    created_at TEXT NOT NULL,
    PRIMARY KEY (vault_id, scope_type, scope_id, suppression_kind, key_hmac)
);

CREATE TABLE IF NOT EXISTS ai_memory_operation (
    operation_id TEXT PRIMARY KEY,
    action TEXT NOT NULL,
    target_id TEXT,
    status TEXT NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    result_meta TEXT,
    created_at TEXT NOT NULL,
    completed_at TEXT
);

CREATE TABLE IF NOT EXISTS ai_usage_attempt (
    id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL,
    context_plan_id TEXT,
    purpose TEXT NOT NULL CHECK (purpose IN
        ('CHAT', 'MEMORY_EXTRACT', 'SESSION_SUMMARY', 'FORMAT_REPAIR')),
    target_fingerprint TEXT NOT NULL,
    budget_date TEXT NOT NULL,
    budget_timezone TEXT NOT NULL,
    reserved_tokens INTEGER NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    cache_read_tokens INTEGER,
    cache_write_tokens INTEGER,
    usage_source TEXT NOT NULL CHECK (usage_source IN ('provider', 'estimate', 'unknown')),
    state TEXT NOT NULL CHECK (state IN ('RESERVED', 'SENT', 'SETTLED', 'UNKNOWN', 'RELEASED')),
    created_at TEXT NOT NULL,
    settled_at TEXT
);

CREATE TABLE IF NOT EXISTS ai_usage_daily (
    budget_date TEXT NOT NULL,
    budget_timezone TEXT NOT NULL,
    target_fingerprint TEXT NOT NULL,
    purpose TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    charged_tokens INTEGER NOT NULL DEFAULT 0,
    reserved_tokens INTEGER NOT NULL DEFAULT 0,
    input_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    cache_write_tokens INTEGER NOT NULL DEFAULT 0,
    unknown_attempts INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (budget_date, budget_timezone, target_fingerprint, purpose)
);

CREATE TABLE IF NOT EXISTS ai_chat_storage (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision INTEGER NOT NULL DEFAULT 1,
    history_limit_bytes INTEGER NOT NULL DEFAULT 268435456 CHECK (history_limit_bytes > 0),
    attachment_limit_bytes INTEGER NOT NULL DEFAULT 268435456 CHECK (attachment_limit_bytes > 0),
    history_bytes INTEGER NOT NULL DEFAULT 0 CHECK (history_bytes >= 0),
    reserved_history_bytes INTEGER NOT NULL DEFAULT 0 CHECK (reserved_history_bytes >= 0),
    attachment_bytes INTEGER NOT NULL DEFAULT 0 CHECK (attachment_bytes >= 0)
);

INSERT OR IGNORE INTO ai_chat_storage(id) VALUES (1);
