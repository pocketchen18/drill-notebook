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
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_knowledge_point_bank ON knowledge_point(bank_id, category, id);

CREATE TABLE IF NOT EXISTS knowledge_point_question (
    knowledge_point_id INTEGER NOT NULL REFERENCES knowledge_point(id) ON DELETE CASCADE,
    question_id INTEGER NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    PRIMARY KEY (knowledge_point_id, question_id)
);

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
    id INTEGER PRIMARY KEY CHECK (id = 1),
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

