-- Week 8: multilingual lecture processing and smart video discovery.
-- Additive only: existing tables, rows and checkpoints are preserved.

-- Per-lecture language preference and detected language.
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

-- One translation attempt per (source transcript fingerprint, settings hash).
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

CREATE INDEX IF NOT EXISTS idx_lecture_translation_lecture
    ON lecture_translation(lecture_id, id);

-- Per-segment translation state, so a failed run resumes only the gaps.
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

CREATE INDEX IF NOT EXISTS idx_translation_segment_translation
    ON translation_segment(translation_id, sequence);

-- Editable terminology. Built-in rows are seeded once and marked is_builtin.
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

-- Topic profile used for local semantic recommendations.
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

-- Ranked local recommendations (actual cosine similarity).
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

-- Provider search cache (no media is downloaded here).
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

CREATE INDEX IF NOT EXISTS idx_video_discovery_cache_expiry
    ON video_discovery_cache(expires_at);

-- Normalized discovered videos, provider IDs and canonical URLs for dedupe.
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

-- Built-in glossary seed (idempotent).
INSERT OR IGNORE INTO translation_glossary
    (term, translation, source_language, target_language, case_sensitive, enabled, is_builtin, updated_at)
VALUES
    ('embedding', 'эмбеддинг', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('embeddings', 'эмбеддинги', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('retrieval', 'поиск релевантной информации', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('fine-tuning', 'дообучение', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('fine tuning', 'дообучение', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('prompt', 'промпт', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('token', 'токен', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('tokens', 'токены', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('pipeline', 'конвейер', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('checkpoint', 'контрольная точка', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('inference', 'инференс', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('latency', 'задержка', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('chunk', 'чанк', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z'),
    ('chunks', 'чанки', 'en', 'ru', 0, 1, 1, '2026-01-01T00:00:00Z');
