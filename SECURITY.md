# Security Policy

## Supported versions

Materia is developed on the `main` branch. Security fixes land there first;
older commits and archived branches are not supported.

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

Use GitHub's private vulnerability reporting (Security → Report a vulnerability)
or contact the repository owner directly. Include:

- affected version or commit SHA,
- steps to reproduce,
- impact and affected component,
- any suggested fix.

We aim to acknowledge a report within a few business days and will coordinate a
disclosure date with you.

## Security model

Materia is designed to run self-hosted, usually on a trusted local network.

- **Secrets**: read from environment variables or a local `.env` that is
  excluded from Git. Never commit `.env`, `*.key`, `*.pem`, database files or
  `.secrets/`.
- **Provider credentials**: user-supplied cloud API keys are entered through
  the settings UI and stored encrypted at rest (AES-256-GCM) with a
  per-installation master key (`LP_MASTER_KEY` or `.secrets/master.key`).
- **Admin surfaces**: `/settings`, `/diagnostics`, `/code-review` are
  restricted to loopback unless `LP_ADMIN_TOKEN` is configured. State-changing
  requests use CSRF tokens.
- **Outbound requests**: URL imports are validated against SSRF (scheme, DNS,
  private/link-local/loopback ranges). Gitea integration uses repository and
  host allow-lists and verifies webhook HMAC signatures.
- **File Agent**: filesystem access is scoped to the configured project root;
  writes require explicit approval and are path-guarded against symlink escape.
- **Logs and errors**: secrets are redacted; user-visible stage errors are
  sanitized and do not include absolute paths.

## Hardening checklist for deployments

1. Set a fresh `LP_MASTER_KEY` (`head -c 32 /dev/urandom | base64`) and keep it
   outside the repository.
2. Set `LP_ADMIN_TOKEN` if the UI is reachable by more than one user.
3. Put the service behind HTTPS (reverse proxy) before exposing it outside a
   trusted network.
4. Use a dedicated Gitea token with minimal scopes and configure
   `GITEA_ALLOWED_REPOS` / `GITEA_TRUSTED_HOSTS`.
5. Leave `YTDLP_COOKIES_MODE=none` unless you intentionally need authenticated
   downloads; never commit cookie files.
6. Keep the SQLite database and media storage outside the repository and back
   them up separately.

## What this project never does

- It does not ship or embed real API keys, tokens, passwords or private keys.
- It does not bypass platform protections, bot checks or DRM.
- It does not auto-merge or auto-approve pull requests in code review.
- It does not execute untrusted code from pull requests in CI with secrets.
