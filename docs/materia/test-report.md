# Materia — отчёт о тестировании

Дата: 2026-10-19. Ветка `feature/materia-rebranding-scenarios`.

## Автоматические тесты

`JAVA_HOME=/c/tools/jdk-25.0.2-full ./mvnw verify -Dspring-boot.repackage.skip=true`

- backend: **553 теста**, 0 failures, 0 errors, 1 skipped;
- private-ai-chat: **28 тестов**, 0 failures;
- итого: **581 тест**, BUILD SUCCESS.

### Новые тесты

| Класс | Что проверяет |
|---|---|
| `ScenarioCatalogTest` | стабильные ID, версия, наличие шаблонов, no-LLM сценарии |
| `ScenarioResolverTest` | рекомендации, ручные override, fail-fast неизвестного ID, fingerprint |
| `ScenarioCompatibilityTest` | предупреждения, `INCOMPATIBLE`, отсутствующий шаблон, legacy-матрица |
| `ScenarioOutputValidatorTest` | пустой результат, потерянные числа, академические разделы, эхо инструкций |
| `TranscriptDocumentBuilderTest` | порядок, повторы, таймкоды, перевод/оригинал |
| `NotesAssemblerPolicyTest` | SEMANTIC/STRUCTURED/CHRONOLOGICAL/DIALOGUE |
| `ScenarioPromptIntegrationTest` | шаблон и контракт в system, `<transcript>` в user, заголовок и отсутствие summary |
| `PipelineCoordinatorIntegrationTest` | verbatim без LLM; смена сценария → только TEXT_PROCESSING+EXPORT, Whisper один раз, варианты сохраняются |
| `AddMaterialImportWebTest` | карточки сценариев, сохранение сценария перед запуском |
| `SchemaMigratorTest` | V12, колонки `scenario`, идемпотентность |

## Реальный E2E (локальный экземпляр, Ollama + whisper.cpp)

Изолированный storage `target/e2e-materia2`, порт 18141,
`OLLAMA_CHAT_MODEL=qwen3:1.7b`, `OLLAMA_DISABLE_THINKING=true`.

| Материал | Сценарий | Фактический результат |
|---|---|---|
| TTS-аудио «блины» | `recipe` | рецепт с 200 г муки, 2 яйца, 300 мл молока, щепоткой соли, 2 мин жарки, 20 мин тесту; академических разделов нет; warnings пусты |
| TTS-аудио диалог | `dialogue` | реплики по порядку, повтор «Привет. Здравствуй.» сохранён, имена не выдуманы, FAQ нет |
| TTS-аудио история | `short-summary` | краткое содержание, события в порядке, детали, итог; без «Определений» и «Контрольных вопросов» |
| TTS-аудио история | `verbatim` | `# Транскрипция`, 4 сегмента с таймкодами `[00:00:00]…`, порядок и текст сохранены; LLM не вызывался |
| Английское TTS-аудио | `translation` | 4/4 сегмента переведены EN→RU, оригинал доступен, документ `# Перевод` собран без генерации конспекта |
| Видео `sample-lecture-ru.mp4` | `verbatim` + slides | обложка, слайды, DOCX/Markdown; используется для E2E карточки/редактора |

Переключение `recipe → short-summary` на одном материале:
план содержал только `TEXT_PROCESSING` и `EXPORT`; fingerprints
`TRANSCRIPTION`/`AUDIO_EXTRACTION` не изменились; создан второй вариант
(`RECIPE`, затем `SHORT_SUMMARY`), старый сохранён.

## UI / браузерные тесты

`cd e2e && LP_BASE_URL=http://127.0.0.1:18141 LP_E2E_LECTURE=<video-id> LP_ADMIN_TOKEN=... LP_E2E_UPLOAD=1 npx playwright test`

- **30 passed, 0 failed**.
- Проверены: бренд `materia`, отсутствие старого названия, карточки сценариев,
  мобильная ширина 390 px, локальный HTMX, CSRF, редактор (вкладки, dirty-state),
  библиотека/фильтры, загрузка файла, карточка/редактор без ссылок на Support.

`python scripts/smoke-ui.py --base-url http://127.0.0.1:18141 --admin-token ...`:
все основные страницы 200, `SMOKE OK` (FFmpeg/Whisper не были в PATH только у
первого запуска; во втором экземпляре диагностика зелёная для нужных инструментов).

## Ограничения проверок

- Live-проверки DeepSeek не выполнялись: используется локальная Ollama.
- Diarization отсутствует; сценарий «Диалоги» сохраняет порядок, но не
  определяет говорящих.
- Проверки качества эвристические; предупреждение не блокирует документ.
- E2E-набор требует отдельного экземпляра с обработанным видео и admin-token.
