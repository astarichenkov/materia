# Запуск и проверка Lecture Processor на Windows

Единый способ запуска — `scripts/run-local.ps1`. Он собирает backend из текущего
рабочего дерева, останавливает/обнаруживает старый экземпляр и проверяет версию
после старта.

## Сборка + запуск

```powershell
cd C:\Projects\lecture-processor
powershell -ExecutionPolicy Bypass -File scripts\run-local.ps1
```

Параметры:

```powershell
# другой порт и явный каталог данных
powershell -ExecutionPolicy Bypass -File scripts\run-local.ps1 -Port 18090 `
  -StorageRoot C:\Projects\lecture-processor\target\e2e-storage

# перезапуск актуальной сборки
powershell -ExecutionPolicy Bypass -File scripts\run-local.ps1 -Restart

# остановить только Lecture Processor на порту
powershell -ExecutionPolicy Bypass -File scripts\run-local.ps1 -Stop -Port 8080

# сборка без тестов (по умолчанию) / с тестами
powershell -ExecutionPolicy Bypass -File scripts\run-local.ps1 -SkipBuild
powershell -ExecutionPolicy Bypass -File scripts\run-local.ps1 -RunTests
```

Launcher никогда не убивает посторонний процесс на порту. Если порт занят не
Lecture Processor, он завершается с ошибкой. `-Restart` останавливает только
процесс, в командной строке которого есть `LectureProcessorApplication` или
`lecture-processor-backend`.

## Проверка версии

```powershell
Invoke-RestMethod http://localhost:8080/api/app/version | Format-List
```

Endpoint возвращает application, version, commit, buildTime, javaVersion,
javaVendor, pid и startedAt. Секретов и путей хранения там нет.

Commit и время сборки также пишутся в лог строкой `BUILD ...` при старте.

## Health / smoke

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
python scripts\smoke-ui.py --base-url http://127.0.0.1:8080
python scripts\smoke-ui.py --base-url http://127.0.0.1:8080 --upload target\e2e-test.mp4
```

Короткий MP4 для проверки:

```powershell
ffmpeg -y -f lavfi -i testsrc=size=320x240:rate=15 -f lavfi -i sine=frequency=440 `
  -t 3 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest target\e2e-test.mp4
```

## Browser smoke (Playwright)

```powershell
$env:LP_BASE_URL = "http://127.0.0.1:18090"
npx playwright test --config e2e\playwright.config.mjs
# с реальной загрузкой файла:
$env:LP_E2E_UPLOAD = "1"
npx playwright test --config e2e\playwright.config.mjs
```

## Логи

Launcher пишет `logs\app-<timestamp>.log` и `logs\app-<timestamp>.err.log`.
Для ошибки UI в ответе есть заголовок `X-Correlation-Id` и тот же id на странице
ошибки; по нему ищется stack trace в логе.

## Данные и SQLite

- Launcher предпочитает `<repo>ackend\data\lecture-processor.db`, если он
  существует (эту базу фактически использовал старый `mvn spring-boot:run`),
  иначе `<repo>\data\lecture-processor.db`. Каталог всегда можно задать через
  `-StorageRoot`.
- Старый запуск через `mvn spring-boot:run` использовал `<repo>\backend\data`
  из-за рабочего каталога модуля. Launcher об этом предупреждает, но ничего не
  удаляет и не переносит автоматически.
- Перед изменениями схемы делайте backup с учётом WAL:
  `sqlite3 data\lecture-processor.db ".backup 'data\backup-$(date +%Y%m%d).db'"`
  (в Windows проще выполнить `.backup` через sqlite3 CLI; при `journal_mode=delete`
  WAL-файлов может не быть).
- Для E2E всегда задавайте отдельный `-StorageRoot`.

## Rollback

1. Остановить: `-Stop`.
2. Переключить рабочее дерево на предыдущий commit (без сброса пользовательских
   данных): `git checkout <previous-commit>`.
3. Запустить launcher; он соберёт предыдущую версию.
4. Если менялась схема — восстановить backup через `.restore`/копирование при
   остановленном приложении.
