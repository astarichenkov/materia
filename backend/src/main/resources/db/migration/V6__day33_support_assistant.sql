-- Day 33: AI support assistant, support tickets and tool trace.
-- Additive only: existing tables and data are preserved.

CREATE TABLE IF NOT EXISTS support_ticket (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    scope       TEXT NOT NULL,
    owner       TEXT NOT NULL,
    lecture_id  TEXT,
    subject     TEXT NOT NULL,
    category    TEXT NOT NULL,
    status      TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    resolved_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_support_ticket_scope ON support_ticket(scope, updated_at);
CREATE INDEX IF NOT EXISTS idx_support_ticket_lecture ON support_ticket(lecture_id);

CREATE TABLE IF NOT EXISTS support_message (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    ticket_id      INTEGER NOT NULL,
    role           TEXT NOT NULL,
    content        TEXT NOT NULL,
    created_at     TEXT NOT NULL,
    lecture_id     TEXT,
    job_id         TEXT,
    citations_json TEXT,
    tools_json     TEXT,
    provider       TEXT,
    model          TEXT,
    grounded       INTEGER,
    duration_ms    INTEGER,
    input_tokens   INTEGER,
    output_tokens  INTEGER,
    estimated_cost REAL,
    cost_currency  TEXT,
    tool_calls     INTEGER,
    tool_failures  INTEGER,
    feedback       INTEGER,
    FOREIGN KEY (ticket_id) REFERENCES support_ticket(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_support_message_ticket ON support_message(ticket_id, id);

CREATE TABLE IF NOT EXISTS support_tool_call (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id  INTEGER,
    ticket_id   INTEGER,
    tool_name   TEXT NOT NULL,
    ok          INTEGER NOT NULL,
    duration_ms INTEGER,
    summary     TEXT,
    created_at  TEXT NOT NULL,
    FOREIGN KEY (ticket_id) REFERENCES support_ticket(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_support_tool_call_ticket ON support_tool_call(ticket_id, id);
