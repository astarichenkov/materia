# Contributing to Materia

Thanks for your interest. This document describes the practical workflow.

## Development setup

Requirements: Java 25, Maven (or the bundled `./mvnw`), and for real processing
FFmpeg/FFprobe, whisper.cpp with a model, Ollama, and optionally `yt-dlp`.

```bash
git clone <your-fork>
cd materia
./mvnw verify
```

See `README.md` and `docs/installation-windows.md` for toolchain details.

## Branching and commits

- Base work on the `main` branch.
- Use focused commits with an imperative subject, for example
  `fix(pipeline): keep cancelled jobs consistent`.
- Keep unrelated refactors out of feature commits.
- Do not commit generated data: `target/`, databases, media files, logs,
  `.env`, credentials or archives.

## Tests

Every change must keep the build green:

```bash
./mvnw verify
```

Frontend smoke/E2E (optional but recommended for UI changes):

```bash
python scripts/smoke-ui.py --base-url http://127.0.0.1:8080
cd e2e && npx playwright test
```

Tests must not call paid APIs. Use the local test doubles (for example the
DeepSeek provider test double) and fake credentials only.

## Pull requests

- Describe the problem, the change and how it was verified.
- Include tests for new behavior and security-relevant changes.
- Do not include secrets, personal data or internal infrastructure details in
  code, docs, fixtures, screenshots or commit messages.
- Keep GitHub Actions permissions minimal; do not add workflows that expose
  secrets to pull requests from forks.

## Security

Never disclose a vulnerability in a public issue. See `SECURITY.md`.
