-- Day 35: Lecture Editor, revision history and revision-aware re-export.
-- Additive only: existing rows, artifacts and checkpoints are preserved.

-- Immutable revision of an editable document (transcript / translation / notes).
-- The raw Whisper artifact (transcript.json) is never overwritten by an edit.
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
    UNIQUE (lecture_id, document_type, revision_number),
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_document_revision_lecture
    ON lecture_document_revision(lecture_id, document_type, revision_number);

-- Per-segment human correction / review state. Overlays the working segment
-- rows; the original model output stays available in the revision history.
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
    UNIQUE (lecture_id, document_type, sequence),
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_segment_correction_lecture
    ON lecture_segment_correction(lecture_id, document_type);

-- Explicit staleness of dependent documents so the UI never shows stale data as
-- current. Values: CURRENT / STALE.
CREATE TABLE IF NOT EXISTS lecture_document_state (
    lecture_id    TEXT NOT NULL,
    document_type TEXT NOT NULL,
    status        TEXT NOT NULL,
    reason        TEXT,
    updated_at    TEXT NOT NULL,
    PRIMARY KEY (lecture_id, document_type),
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

-- Versioned export records. The artifact itself lives in the lecture storage
-- directory; the row keeps the exact source revisions used to build it.
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
    created_at              TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_lecture_export_lecture
    ON lecture_export(lecture_id, id);
