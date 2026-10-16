CREATE TABLE IF NOT EXISTS lecture (
    id                   TEXT PRIMARY KEY,
    original_filename    TEXT NOT NULL,
    stored_filename      TEXT NOT NULL,
    size_bytes           INTEGER NOT NULL,
    content_type         TEXT,
    status               TEXT NOT NULL,
    error_message        TEXT,
    duration_ms          INTEGER,
    clean_transcript     INTEGER NOT NULL DEFAULT 0,
    extract_slides       INTEGER NOT NULL DEFAULT 0,
    raw_transcript_path  TEXT,
    cleaned_text         TEXT,
    notes_markdown       TEXT,
    docx_path            TEXT,
    created_at           TEXT NOT NULL,
    updated_at           TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS transcript_segment (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id   TEXT NOT NULL,
    sequence     INTEGER NOT NULL,
    start_ms     INTEGER NOT NULL,
    end_ms       INTEGER NOT NULL,
    text         TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_segment_lecture ON transcript_segment(lecture_id, sequence);

CREATE TABLE IF NOT EXISTS lecture_chunk (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id    TEXT NOT NULL,
    sequence      INTEGER NOT NULL,
    start_ms      INTEGER NOT NULL,
    end_ms        INTEGER NOT NULL,
    text          TEXT NOT NULL,
    embedding     BLOB,
    embedding_dim INTEGER,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chunk_lecture ON lecture_chunk(lecture_id, sequence);

CREATE TABLE IF NOT EXISTS chat_message (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id   TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    role         TEXT NOT NULL,
    content      TEXT NOT NULL,
    sources_json TEXT,
    created_at   TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_chat_session ON chat_message(lecture_id, session_id, id);

CREATE TABLE IF NOT EXISTS slide (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id   TEXT NOT NULL,
    sequence     INTEGER NOT NULL,
    timestamp_ms INTEGER NOT NULL,
    image_path   TEXT NOT NULL,
    phash        TEXT,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_slide_lecture ON slide(lecture_id, sequence);

-- Day 29 benchmark console: persisted runs (history, comparison, recommended).
CREATE TABLE IF NOT EXISTS benchmark_run (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id       TEXT NOT NULL,
    provider         TEXT NOT NULL,
    model            TEXT NOT NULL,
    temperature      REAL,
    max_tokens       INTEGER,
    context_window   INTEGER,
    thinking         INTEGER,
    prompt_template  TEXT,
    quantization     TEXT,
    input_tokens     INTEGER,
    output_tokens    INTEGER,
    thinking_tokens  INTEGER,
    llm_calls        INTEGER,
    chunk_count      INTEGER,
    llm_duration_ms  INTEGER,
    total_duration_ms INTEGER,
    tokens_per_second REAL,
    peak_ram_mb      INTEGER,
    peak_vram_mb     INTEGER,
    quality_score    REAL,
    quality_notes    TEXT,
    status           TEXT NOT NULL,
    error            TEXT,
    output           TEXT,
    recommended      INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_benchmark_run_lecture ON benchmark_run(lecture_id, id);

-- Recommended / default LLM configuration used by new lecture-processing jobs.
CREATE TABLE IF NOT EXISTS app_setting (
    setting_key   TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at    TEXT NOT NULL
);

-- Lightweight phase timings for the full conversion pipeline (LLM vs total).
CREATE TABLE IF NOT EXISTS lecture_stage_timing (
    lecture_id  TEXT NOT NULL,
    stage       TEXT NOT NULL,
    duration_ms INTEGER NOT NULL,
    PRIMARY KEY (lecture_id, stage),
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

-- Per-installation encrypted provider credentials (Day 32). The master key that
-- decrypts `ciphertext` lives outside the database; see ProviderCredentialService.
CREATE TABLE IF NOT EXISTS provider_credential (
    provider_id     TEXT PRIMARY KEY,
    ciphertext      TEXT NOT NULL,
    key_hint        TEXT,
    encryption      TEXT NOT NULL DEFAULT 'aes-256-gcm-v1',
    updated_at      TEXT NOT NULL,
    last_success_at TEXT,
    last_error      TEXT
);

-- Day 32: automated AI code review for Gitea (also created by V5 for existing DBs).
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
CREATE TABLE IF NOT EXISTS code_review_cursor (
    repository  TEXT PRIMARY KEY,
    last_sha    TEXT,
    updated_at  TEXT NOT NULL
);
