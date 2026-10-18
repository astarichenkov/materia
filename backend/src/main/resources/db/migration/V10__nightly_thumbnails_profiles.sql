-- Nightly roadmap (LIBRARY-1 + UX-5/PIPELINE-1).
-- Additive only: existing rows, artifacts and checkpoints are preserved.

-- Cached lecture thumbnail. The image itself lives at
-- lectures/<lectureId>/thumbnail.jpg and is never a slide image.
CREATE TABLE IF NOT EXISTS lecture_thumbnail (
    lecture_id    TEXT PRIMARY KEY,
    status        TEXT NOT NULL,
    source        TEXT NOT NULL,
    relative_path TEXT,
    size_bytes    INTEGER NOT NULL DEFAULT 0,
    error         TEXT,
    updated_at    TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_lecture_thumbnail_status
    ON lecture_thumbnail(status, updated_at);

-- Selected output profile for a lecture (material type + document format).
-- The applied column set records what actually produced the current notes so
-- the rerun planner can explain what will change.
CREATE TABLE IF NOT EXISTS lecture_processing_profile (
    lecture_id      TEXT PRIMARY KEY,
    material_type   TEXT NOT NULL,
    output_format   TEXT NOT NULL,
    detail_level    TEXT NOT NULL,
    prompt_template TEXT,
    updated_at      TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);

-- Immutable archive of every produced document variant. The working artifact
-- (notes.md / notes.docx) is still overwritten by the pipeline; a variant keeps
-- the previous result downloadable forever.
CREATE TABLE IF NOT EXISTS lecture_output_variant (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    lecture_id      TEXT NOT NULL,
    material_type   TEXT NOT NULL,
    output_format   TEXT NOT NULL,
    detail_level    TEXT NOT NULL,
    prompt_template TEXT,
    provider        TEXT,
    model           TEXT,
    notes_markdown  TEXT NOT NULL,
    markdown_artifact TEXT,
    docx_artifact   TEXT,
    content_hash    TEXT,
    created_at      TEXT NOT NULL,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_lecture_variant_lecture
    ON lecture_output_variant(lecture_id, id);
