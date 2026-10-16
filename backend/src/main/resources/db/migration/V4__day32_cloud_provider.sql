-- Day 32 preparation: cloud LLM provider (DeepSeek) credentials.
-- Additive only: existing tables and data are preserved.
--
-- SECURITY: this table stores ONLY encrypted credential material. The
-- per-installation master key that can decrypt it lives outside the database
-- (environment variable or a restricted file), never in this table and never in
-- source control. The format is versioned inside `ciphertext` (e.g. `v1:...`).

CREATE TABLE IF NOT EXISTS provider_credential (
    provider_id     TEXT PRIMARY KEY,
    ciphertext      TEXT NOT NULL,
    key_hint        TEXT,
    encryption      TEXT NOT NULL DEFAULT 'aes-256-gcm-v1',
    updated_at      TEXT NOT NULL,
    last_success_at TEXT,
    last_error      TEXT
);
