-- Week 7 / Day 31 developer assistant migration (version 3).
-- Additive only: existing tables and data are preserved.
--
-- Project documentation RAG lives in its own collection/table on purpose:
-- it must never be mixed with the per-lecture `lecture_chunk` index that
-- powers lecture Q&A.

CREATE TABLE IF NOT EXISTS doc_chunk (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    source_path   TEXT NOT NULL,
    source_title  TEXT,
    heading       TEXT,
    sequence      INTEGER NOT NULL,
    text          TEXT NOT NULL,
    embedding     BLOB,
    embedding_dim INTEGER,
    indexed_at    TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_doc_chunk_source ON doc_chunk(source_path, sequence);
