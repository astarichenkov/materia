-- Day 32: automated AI code review for Gitea.
-- Additive only: existing tables and data are preserved.

CREATE TABLE IF NOT EXISTS code_review_job (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    repository          TEXT NOT NULL,
    pr_number           INTEGER NOT NULL,
    head_sha            TEXT NOT NULL,
    base_sha            TEXT,
    config_version      TEXT NOT NULL,
    idempotency_key     TEXT NOT NULL UNIQUE,
    status              TEXT NOT NULL,
    triggered_by        TEXT,
    provider            TEXT,
    model               TEXT,
    fallback            INTEGER NOT NULL DEFAULT 0,
    cloud               INTEGER NOT NULL DEFAULT 0,
    partial             INTEGER NOT NULL DEFAULT 0,
    summary             TEXT,
    overall_risk        TEXT,
    error               TEXT,
    published_comment_id TEXT,
    findings_count      INTEGER NOT NULL DEFAULT 0,
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    estimated_cost      REAL,
    cost_currency       TEXT,
    duration_ms         INTEGER,
    queue_ms            INTEGER,
    gitea_ms            INTEGER,
    retrieval_ms        INTEGER,
    llm_ms              INTEGER,
    publish_ms          INTEGER,
    llm_calls           INTEGER,
    changed_files       INTEGER,
    analyzed_files      INTEGER,
    skipped_files       TEXT,
    created_at          TEXT NOT NULL,
    started_at          TEXT,
    finished_at         TEXT
);

CREATE INDEX IF NOT EXISTS idx_code_review_job_repo_pr ON code_review_job(repository, pr_number, id);
CREATE INDEX IF NOT EXISTS idx_code_review_job_sha ON code_review_job(repository, pr_number, head_sha);

CREATE TABLE IF NOT EXISTS code_review_finding (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id      INTEGER NOT NULL,
    category    TEXT NOT NULL,
    severity    TEXT NOT NULL,
    file_path   TEXT,
    line_number INTEGER,
    title       TEXT NOT NULL,
    description TEXT,
    suggestion  TEXT,
    confidence  REAL,
    FOREIGN KEY (job_id) REFERENCES code_review_job(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_code_review_finding_job ON code_review_finding(job_id);

-- Source-code RAG collection, versioned by commit SHA. Deliberately separate
-- from doc_chunk and lecture_chunk.
CREATE TABLE IF NOT EXISTS code_chunk (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    repository    TEXT NOT NULL,
    git_ref       TEXT NOT NULL,
    commit_sha    TEXT NOT NULL,
    file_path     TEXT NOT NULL,
    chunk_type    TEXT NOT NULL,
    symbol        TEXT,
    start_line    INTEGER,
    end_line      INTEGER,
    content_hash  TEXT,
    text          TEXT NOT NULL,
    embedding     BLOB,
    embedding_dim INTEGER,
    indexed_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_code_chunk_repo_ref ON code_chunk(repository, git_ref);
CREATE INDEX IF NOT EXISTS idx_code_chunk_file ON code_chunk(repository, commit_sha, file_path);

-- Pull-based fallback cursor when webhooks cannot reach the application.
CREATE TABLE IF NOT EXISTS code_review_cursor (
    repository  TEXT PRIMARY KEY,
    last_sha    TEXT,
    updated_at  TEXT NOT NULL
);
