# Third-party notices

Materia is licensed under the Apache License, Version 2.0 (see `LICENSE`).
This file lists third-party components that are either bundled in this
repository or required at runtime.

## Bundled in this repository

| Component | Version | License | Location |
|---|---|---|---|
| htmx | 1.9.12 | BSD 2-Clause | `backend/src/main/resources/static/vendor/htmx.min.js` |

The htmx file keeps its original license header:

```
/*! htmx.org 1.9.12 | BSD 2-Clause | https://htmx.org/ */
```

## Resolved at build time (not redistributed here)

The backend and the private chat module use Maven dependencies that are
downloaded during the build and are **not** vendored in this repository,
including:

- Spring Boot, Spring Framework, Spring AI — Apache License 2.0
- Apache POI — Apache License 2.0
- Jackson — Apache License 2.0
- SQLite JDBC (xerial) — Apache License 2.0
- Flyway-style schema handling is implemented in-project (no Flyway dependency)
- JUnit, AssertJ, Mockito (test scope) — Eclipse Public License 2.0 / Apache 2.0 / MIT

The browser end-to-end tests use Playwright (Apache License 2.0) as a
development dependency; it is not part of the deployed application.

## External tools installed by the user

Materia orchestrates external programs but does not distribute them:

- **FFmpeg / FFprobe** — LGPL 2.1+ or GPL depending on the build; obtain a
  build that matches your distribution requirements.
- **whisper.cpp** — MIT License.
- **yt-dlp** — Unlicense (public domain).
- **Ollama** and downloaded models — each model carries its own license;
  review it before use and redistribution.

## Trademarks and platforms

YouTube, VK, Rutube, Gitea, GitHub, DeepSeek and Ollama are trademarks of
their respective owners. Materia is not affiliated with or endorsed by them.
