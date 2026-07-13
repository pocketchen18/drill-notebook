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

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now'))
);

CREATE VIRTUAL TABLE IF NOT EXISTS question_fts USING fts5(
    stem, answer, analysis, tags,
    content='question', content_rowid='id'
);
