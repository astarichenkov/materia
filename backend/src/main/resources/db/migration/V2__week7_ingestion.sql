-- Week 7 ingestion & resumable pipeline migration (version 2).
-- Additive only: existing tables and data are preserved.

CREATE TABLE IF NOT EXISTS schema_migration (
    version    INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);

-- Logical media source attached to a lecture (local upload or remote URL).
CREATE TABLE IF NOT EXISTS media_source (
    lecture_id       TEXT PRIMARY KEY,
    kind             TEXT NOT NULL,
    original_url     TEXT,
    original_filename TEXT,
    provider         TEXT,
    external_id      TEXT,
    title            TEXT,
    uploader         TEXT,
    duration_ms      INTEGER,
    quality          TEXT,
    audio_only       INTEGER NOT NULL DEFAULT 0,
    metadata_json    TEXT,
    checksum         TEXT,
    created_at       TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

-- One job row per lecture; holds the lease used for atomic claiming.
CREATE TABLE IF NOT EXISTS pipeline_job (
    lecture_id   TEXT PRIMARY KEY,
    state        TEXT NOT NULL,
    current_stage TEXT,
    attempts     INTEGER NOT NULL DEFAULT 0,
    lease_owner  TEXT,
    lease_until  TEXT,
    error        TEXT,
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_pipeline_job_state ON pipeline_job(state, lease_until);

-- Per-stage checkpoint: fingerprint + artifact manifest decide whether to rerun.
CREATE TABLE IF NOT EXISTS stage_checkpoint (
    lecture_id            TEXT NOT NULL,
    stage                 TEXT NOT NULL,
    state                 TEXT NOT NULL,
    fingerprint           TEXT,
    attempt               INTEGER NOT NULL DEFAULT 0,
    started_at            TEXT,
    finished_at           TEXT,
    error                 TEXT,
    artifact_manifest_json TEXT,
    PRIMARY KEY (lecture_id, stage),
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

-- Live download state for the UI (bytes/speed/eta). Best-effort persistence.
CREATE TABLE IF NOT EXISTS download_state (
    lecture_id       TEXT PRIMARY KEY,
    status           TEXT NOT NULL,
    bytes_downloaded INTEGER,
    bytes_total      INTEGER,
    percent          REAL,
    speed_bps        REAL,
    eta_seconds      INTEGER,
    error            TEXT,
    updated_at       TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
