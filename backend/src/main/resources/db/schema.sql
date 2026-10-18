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

-- Day 33: AI support assistant (tickets, messages, tool trace).
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

-- Week 8: multilingual lecture processing and smart video discovery
-- (also created by migration V8 for existing databases).
CREATE TABLE IF NOT EXISTS lecture_language (
    lecture_id          TEXT PRIMARY KEY,
    language_mode       TEXT NOT NULL DEFAULT 'AUTO',
    detected_language   TEXT,
    language_confidence REAL,
    detection_source    TEXT,
    translation_mode    TEXT NOT NULL DEFAULT 'TRANSLATE_TO_RUSSIAN',
    target_language     TEXT NOT NULL DEFAULT 'ru',
    auto_translate      INTEGER NOT NULL DEFAULT 1,
    updated_at          TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS lecture_translation (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id            TEXT NOT NULL,
    source_fingerprint    TEXT NOT NULL,
    source_language       TEXT,
    target_language       TEXT NOT NULL,
    provider              TEXT NOT NULL,
    model                 TEXT NOT NULL,
    prompt_version        TEXT NOT NULL,
    glossary_version      TEXT NOT NULL,
    settings_hash         TEXT NOT NULL,
    status                TEXT NOT NULL,
    segments_total        INTEGER NOT NULL DEFAULT 0,
    segments_translated   INTEGER NOT NULL DEFAULT 0,
    segments_failed       INTEGER NOT NULL DEFAULT 0,
    warnings_json         TEXT,
    error                 TEXT,
    input_tokens          INTEGER,
    output_tokens         INTEGER,
    llm_calls             INTEGER NOT NULL DEFAULT 0,
    estimated_cost        REAL,
    cost_currency         TEXT,
    created_at            TEXT NOT NULL,
    updated_at            TEXT NOT NULL,
    UNIQUE (lecture_id, source_fingerprint, settings_hash)
);
CREATE INDEX IF NOT EXISTS idx_lecture_translation_lecture ON lecture_translation(lecture_id, id);
CREATE TABLE IF NOT EXISTS translation_segment (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    translation_id  INTEGER NOT NULL,
    sequence        INTEGER NOT NULL,
    start_ms        INTEGER NOT NULL,
    end_ms          INTEGER NOT NULL,
    source_text     TEXT NOT NULL,
    translated_text TEXT,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    quality         TEXT NOT NULL DEFAULT 'OK',
    warning         TEXT,
    error           TEXT,
    attempts        INTEGER NOT NULL DEFAULT 0,
    UNIQUE (translation_id, sequence),
    FOREIGN KEY (translation_id) REFERENCES lecture_translation(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_translation_segment_translation ON translation_segment(translation_id, sequence);
CREATE TABLE IF NOT EXISTS translation_glossary (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    term            TEXT NOT NULL,
    translation     TEXT NOT NULL,
    source_language TEXT NOT NULL DEFAULT 'en',
    target_language TEXT NOT NULL DEFAULT 'ru',
    case_sensitive  INTEGER NOT NULL DEFAULT 0,
    enabled         INTEGER NOT NULL DEFAULT 1,
    is_builtin      INTEGER NOT NULL DEFAULT 0,
    updated_at      TEXT NOT NULL,
    UNIQUE (term, source_language, target_language)
);
CREATE TABLE IF NOT EXISTS lecture_topic (
    lecture_id    TEXT PRIMARY KEY,
    title         TEXT,
    summary       TEXT,
    key_topics    TEXT,
    keywords      TEXT,
    profile_text  TEXT NOT NULL,
    embedding     BLOB,
    embedding_dim INTEGER,
    source_hash   TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS lecture_similarity (
    lecture_id          TEXT NOT NULL,
    similar_lecture_id  TEXT NOT NULL,
    score               REAL NOT NULL,
    basis               TEXT NOT NULL,
    common_topics       TEXT,
    computed_at         TEXT NOT NULL,
    PRIMARY KEY (lecture_id, similar_lecture_id),
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS video_discovery_cache (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    provider     TEXT NOT NULL,
    query        TEXT NOT NULL,
    filters      TEXT NOT NULL,
    results_json TEXT,
    status       TEXT NOT NULL,
    error        TEXT,
    fetched_at   TEXT NOT NULL,
    expires_at   TEXT NOT NULL,
    UNIQUE (provider, query, filters)
);
CREATE INDEX IF NOT EXISTS idx_video_discovery_cache_expiry ON video_discovery_cache(expires_at);
CREATE TABLE IF NOT EXISTS discovered_video (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    provider            TEXT NOT NULL,
    external_id         TEXT,
    canonical_url       TEXT NOT NULL,
    title               TEXT NOT NULL,
    description         TEXT,
    channel             TEXT,
    duration_ms         INTEGER,
    language            TEXT,
    published_at        TEXT,
    thumbnail_url       TEXT,
    query               TEXT,
    relevance           REAL,
    relevance_basis     TEXT,
    imported_lecture_id TEXT,
    first_seen_at       TEXT NOT NULL,
    last_seen_at        TEXT NOT NULL,
    UNIQUE (provider, external_id)
);
CREATE INDEX IF NOT EXISTS idx_discovered_video_url ON discovered_video(canonical_url);

-- Day 35: lecture editor revisions, corrections, staleness and exports
-- (also created by migration V9 for existing databases).
CREATE TABLE IF NOT EXISTS lecture_document_revision (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id         TEXT NOT NULL,
    document_type      TEXT NOT NULL,
    revision_number    INTEGER NOT NULL,
    content            TEXT NOT NULL,
    content_hash       TEXT NOT NULL,
    parent_revision_id INTEGER,
    edit_source        TEXT NOT NULL,
    description        TEXT,
    created_at         TEXT NOT NULL,
    UNIQUE (lecture_id, document_type, revision_number)
);
CREATE INDEX IF NOT EXISTS idx_document_revision_lecture
    ON lecture_document_revision(lecture_id, document_type, revision_number);
CREATE TABLE IF NOT EXISTS lecture_segment_correction (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id     TEXT NOT NULL,
    document_type  TEXT NOT NULL,
    sequence       INTEGER NOT NULL,
    translation_id INTEGER,
    original_text  TEXT NOT NULL,
    corrected_text TEXT NOT NULL,
    review_status  TEXT NOT NULL,
    note           TEXT,
    provider       TEXT,
    model          TEXT,
    updated_at     TEXT NOT NULL,
    UNIQUE (lecture_id, document_type, sequence)
);
CREATE INDEX IF NOT EXISTS idx_segment_correction_lecture
    ON lecture_segment_correction(lecture_id, document_type);
CREATE TABLE IF NOT EXISTS lecture_document_state (
    lecture_id    TEXT NOT NULL,
    document_type TEXT NOT NULL,
    status        TEXT NOT NULL,
    reason        TEXT,
    updated_at    TEXT NOT NULL,
    PRIMARY KEY (lecture_id, document_type)
);
CREATE TABLE IF NOT EXISTS lecture_export (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id              TEXT NOT NULL,
    variant                 TEXT NOT NULL,
    artifact                TEXT NOT NULL,
    content_hash            TEXT,
    transcript_revision_id  INTEGER,
    translation_revision_id INTEGER,
    notes_revision_id       INTEGER,
    size_bytes              INTEGER NOT NULL DEFAULT 0,
    status                  TEXT NOT NULL,
    error                   TEXT,
    created_at              TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lecture_export_lecture ON lecture_export(lecture_id, id);
