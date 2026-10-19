# UX-4. Единый мастер импорта — реализация и Git-стратегия

> Локальный документ (`docs/` в `.gitignore`). Фиксирует аудит, решения,
> проверки и рекомендации по `main`.

## 1. Git-аудит (2026-10-19)

Проверено реально:

- `git status --short --branch` → `## develop...origin/develop`, рабочее дерево
  чистое;
- `git fetch origin --prune` → новых изменений нет;
- `origin` = приватная Gitea (`http://203.0.113.20:8108/example-org/materia`),
  `github` = публичное зеркало;
- `git remote show origin` → HEAD branch: `develop`, 16 remote-веток;
- `git branch -a --list '*main*'` → только `remotes/github/main` (публичное
  зеркало, 13 коммитов, история переписана `filter-repo`); **в Gitea ветки
  `main` нет**;
- открытые PR (Gitea API): один — `demo/day32-ai-review ->
  feature/week7-day32-code-review` (демонстрационный, с намеренными дефектами).

История линейная:

```
f31c540 (develop, origin/develop) docs: declare develop as the main integration branch
df59a83 (feature/ux3-dashboard-library) тесты UX-3
c70b22f feat(ui): dashboard + paginated library
0fa6335 fix(pipeline): keep cancelled jobs and stage checkpoints consistent
c387205 (feature/ux2-layout) UX-2 финал
...
```

Выводы:

- `feature/ux3-dashboard-library` (`df59a83`) полностью входит в `develop`
  (`f31c540` — только docs-коммит сверху). Расхождений с `origin` нет.
- Неинтегрированных важных веток нет: Week 7/8, UX-2, UX-3 — в линейной
  истории `develop`; `demo/day32-ai-review` намеренно не вливается;
  `feature/week6-*`, `fix/week6-*` — исторические.
- UX-3 не содержит незавершённых изменений: `./mvnw verify` на базе даёт
  455 тестов (без новых), рабочее дерево чистое.

UX-4 создан от `develop` (`f31c540`) как `feature/ux4-import-wizard`, то есть
база включает и UX-2, и UX-3.

### Рекомендация по `main`

1. Владелец подтверждает дату/точку: текущий `develop` — стабильная версия
   (UX-3 + UX-4 после ревью).
2. Только после подтверждения:
   ```bash
   git branch main develop && git push origin main
   # переключить default branch (PATCH /repos/example-org/materia)
   # default_branch=main
   ```
3. Дальше: `main` — интеграционная, фичи — `feature/ux4-...`,
   `feature/ux5-...`; merge только через PR (Gitea) из feature в `main`.
4. `develop` можно оставить как «следующая версия» или удалить — только с
   согласия владельца. Force push не нужен: истории совместимы.
5. PR-порядок для UX-4: `feature/ux4-import-wizard -> main` (после создания
   `main`) либо `-> develop` (если `main` пока не создаётся).

Без подтверждения владельца: default branch не менялся, `main` не создавался,
массовых merge, удалений и переписывания истории не было.

## 2. Аудит существующего импорта

| Точка входа | Что вызывает | Создание лекции | Запуск pipeline |
|---|---|---|---|
| `POST /lectures` (multipart) | `LectureWebController.upload` → `LectureService.create` | да | `LectureJobService.submit` |
| `POST /lectures/from-url` | `LectureWebController.uploadFromUrl` → `RemoteLectureService.createFromUrl` | да (после metadata probe) | `submit` |
| `POST /api/lectures` | `LectureApiController.upload` → `LectureService.create` | да | нет (отдельный `/process`) |
| `POST /api/lectures/from-url` | `LectureApiController.createFromUrl` | да | `submit` |
| `POST /discover/import` | `DiscoveryWebController` → `VideoDiscoveryService.importVideo` | да (или существующая) | `submit` |
| `POST /api/discovery/import` | `DiscoveryApiController` | да (или существующая) | `submit` |
| `GET /lectures/preview` | `RemoteLectureService.preview` → `UrlSafetyValidator` + `yt-dlp --dump-single-json` | нет | нет |
| `GET /api/lectures/metadata` | то же | нет | нет |

Валидация: `LectureService.validate` (пустота, размер `app.upload.max-file-size-bytes`,
расширения `.mp4/.mkv/.mov/.webm/.avi/.m4v`); `UrlSafetyValidator`
(HTTPS/allow-http, allow-list платформ, запрет user-info, DNS-проверка на
публичные адреса, длина ≤ 2048). Ошибки yt-dlp классифицируются
`YtDlpFailure` → `MediaDownloadException` (`reason`, `retryable`, `detail`;
`detail` только в лог).

Что было дублировано/неудобно:

- две формы URL (превью и импорт) с разными наборами параметров;
- ошибки импорта уходили `redirect:/` с сырым `e.getMessage()`;
- не было защиты от повторной отправки;
- `POST /lectures/from-url` мог создать дубль уже имеющегося URL, тогда как
  Discovery — нет.

## 3. Реализация

- `/add` — один мастер: переключатель источника (file / url / discovery),
  общие параметры через `fragments/import-options.html`, сводка перед отправкой,
  повторное использование существующих POST/GET.
- `AddMaterialWebController` только формирует модель: возможности установки
  (`ImportCapabilitiesService`), значения по умолчанию из `MultilingualProperties`,
  read-only подпись модели из `LlmSettingsService`, `requestId`.
- `import-wizard.js` — прогрессивное улучшение: без JS все панели видны и формы
  работают; с JS — переключение, drag&drop, проверка файла, сводка, блокировка
  повторной отправки.
- `ImportDeduplicationService`: requestId (TTL 30 мин, in-memory) + поиск
  существующей лекции по каноническому URL.
- Ошибки: `redirect:/add?url=...` c flash `error` (+ `importErrorUrl` только для
  абсолютных http(s)); ошибка загрузки/URL не создаёт лекцию; переполнение
  очереди показывается как ошибка на карточке, а не как успех.
- Discovery: добавлена ссылка «Открыть в мастере» (`/add?url=...`);
  `POST /discover/import` сохранён.
- Per-job выбор модели не выдуман: показывается read-only «Модель обработки» и
  ссылка в `/settings`.

## 4. Ограничения

- CSRF для `/lectures` и `/lectures/from-url` не добавлялся: этих форм раньше
  не было в CSRF-контуре (CSRF есть у editor/support/settings). Новый контур
  сломал бы `scripts/smoke-ui.py --upload` и старые интеграции.
- Идемпотентность requestId не переживает рестарт приложения и не работает
  между инстансами; для локальной загрузки без requestId дубль возможен.
- Дедупликация по URL блокирует повторный импорт того же видео с другими
  параметрами (как в Discovery); пользователь открывает существующую лекцию
  и запускает обработку из карточки.
- Локальные MP3/M4A/WAV не поддерживаются (UI честно это сообщает).
- Реальный YouTube недоступен из сети стенда (см. раздел проверок).

## 5. Проверки

- `./mvnw verify` → 475 backend (1 skipped) + 28 private-ai, BUILD SUCCESS.
- `scripts/smoke-ui.py` (порт 18098, storage `target/ux4-e2e`): все страницы 200,
  реальная загрузка `target/e2e-test.mp4` → COMPLETED.
- Playwright (LP_E2E_UPLOAD=1): 23 passed, 1 skipped.
- Реальный импорт URL: Rutube `27e733d2...` → download 251 450 803 байт,
  затем pipeline; повторная отправка того же URL → тот же `lectureId`.
- YouTube preview: сетевая ошибка (BLOCKED), UI показал причину + fallback.
