-- Day 34: autonomous file agent — tasks, executions, tool trace and change proposals.
-- Additive only: existing tables and data are preserved.

CREATE TABLE IF NOT EXISTS file_agent_task (
    id                  TEXT PRIMARY KEY,
    workspace           TEXT NOT NULL,
    lecture_id          TEXT,
    goal                TEXT NOT NULL,
    status              TEXT NOT NULL,
    plan                TEXT,
    relevant_files      TEXT,
    provider            TEXT,
    model               TEXT,
    error               TEXT,
    result_summary      TEXT,
    files_searched      INTEGER NOT NULL DEFAULT 0,
    files_read          INTEGER NOT NULL DEFAULT 0,
    files_proposed      INTEGER NOT NULL DEFAULT 0,
    files_changed       INTEGER NOT NULL DEFAULT 0,
    tool_calls          INTEGER NOT NULL DEFAULT 0,
    tool_failures       INTEGER NOT NULL DEFAULT 0,
    planning_ms         INTEGER NOT NULL DEFAULT 0,
    duration_ms         INTEGER NOT NULL DEFAULT 0,
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    estimated_cost      REAL,
    cost_currency       TEXT,
    rollback_attempts   INTEGER NOT NULL DEFAULT 0,
    verification_status TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    completed_at        TEXT
);

CREATE INDEX IF NOT EXISTS idx_file_agent_task_created ON file_agent_task(created_at);
CREATE INDEX IF NOT EXISTS idx_file_agent_task_status ON file_agent_task(status);

CREATE TABLE IF NOT EXISTS file_agent_execution (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id     TEXT NOT NULL,
    phase       TEXT NOT NULL,
    status      TEXT NOT NULL,
    detail      TEXT,
    started_at  TEXT NOT NULL,
    finished_at TEXT,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (task_id) REFERENCES file_agent_task(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_file_agent_execution_task ON file_agent_execution(task_id, id);

CREATE TABLE IF NOT EXISTS file_agent_tool_call (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id     TEXT NOT NULL,
    tool_name   TEXT NOT NULL,
    arguments   TEXT,
    ok          INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    summary     TEXT,
    created_at  TEXT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES file_agent_task(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_file_agent_tool_call_task ON file_agent_tool_call(task_id, id);

CREATE TABLE IF NOT EXISTS file_change_proposal (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id      TEXT NOT NULL,
    version      INTEGER NOT NULL,
    status       TEXT NOT NULL,
    workspace    TEXT NOT NULL,
    lecture_id   TEXT,
    summary      TEXT,
    diff         TEXT,
    provider     TEXT,
    model        TEXT,
    error        TEXT,
    backup_dir   TEXT,
    approved_by  TEXT,
    verification TEXT,
    created_at   TEXT NOT NULL,
    approved_at  TEXT,
    applied_at   TEXT,
    FOREIGN KEY (task_id) REFERENCES file_agent_task(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_file_change_proposal_task ON file_change_proposal(task_id, version);
CREATE INDEX IF NOT EXISTS idx_file_change_proposal_status ON file_change_proposal(status);

CREATE TABLE IF NOT EXISTS proposed_file_change (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    proposal_id      INTEGER NOT NULL,
    path             TEXT NOT NULL,
    change_type      TEXT NOT NULL,
    original_hash    TEXT,
    new_hash         TEXT NOT NULL,
    original_content TEXT,
    new_content      TEXT NOT NULL,
    reason           TEXT,
    FOREIGN KEY (proposal_id) REFERENCES file_change_proposal(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_proposed_file_change_proposal ON proposed_file_change(proposal_id, id);
