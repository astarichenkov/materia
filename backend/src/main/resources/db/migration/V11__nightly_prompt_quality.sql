-- Nightly sprint 2: regeneration progress, prompt quality and template studio.
-- Additive only: existing rows, artifacts and checkpoints are preserved.

-- One row per pipeline run. The persisted checkpoint rows remain the source of
-- truth for stage results; this table records WHICH stages belonged to the run
-- and how far the run itself progressed. That is what makes it possible to show
-- "new generation: TEXT_PROCESSING 7/18" instead of the previous 100% pipeline.
CREATE TABLE IF NOT EXISTS pipeline_run (
    id             TEXT PRIMARY KEY,
    lecture_id     TEXT NOT NULL,
    state          TEXT NOT NULL,
    planned_stages TEXT NOT NULL,
    current_stage  TEXT,
    message        TEXT,
    error          TEXT,
    stage_done     INTEGER NOT NULL DEFAULT 0,
    stage_total    INTEGER NOT NULL DEFAULT 0,
    created_at     TEXT NOT NULL,
    started_at     TEXT,
    finished_at    TEXT,
    FOREIGN KEY (lecture_id) REFERENCES lecture(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_pipeline_run_lecture
    ON pipeline_run(lecture_id, created_at DESC);

-- User-owned prompt templates. Built-in templates stay on the classpath and are
-- never overwritten; a user copy is an explicit new row with its own version.
CREATE TABLE IF NOT EXISTS prompt_template (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    description     TEXT,
    source_template TEXT,
    body            TEXT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    material_types  TEXT,
    output_formats  TEXT,
    built_in        INTEGER NOT NULL DEFAULT 1,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_prompt_template_builtin
    ON prompt_template(built_in, updated_at);
