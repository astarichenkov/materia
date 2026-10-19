# Установка Lecture Processor на Windows

Пошаговая инструкция для чистой Windows-машины. Проверено на Windows 11 +
Git Bash + Java 25.

## 1. Java 25

```powershell
winget install EclipseAdoptium.Temurin.25.JDK
java -version
```

Задайте `JAVA_HOME` на каталог JDK (не JRE). В Git Bash:

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full   # пример; подставьте свой путь
```

## 2. FFmpeg / FFprobe

```powershell
winget install Gyan.FFmpeg
ffmpeg -version
ffprobe -version
```

Либо распакуйте portable-сборку и задайте `FFMPEG_PATH`/`FFPROBE_PATH`
(например `C:\tools\ffmpeg\bin\ffmpeg.exe`).

## 3. whisper.cpp + модель

1. Скачайте сборку `whisper-cli.exe` (CPU или с BLAS).
2. Скачайте модель, например `ggml-small.bin`.
3. Задайте:

```bash
export WHISPER_PATH='C:\tools\whisper\whisper-cli.exe'
export WHISPER_MODEL_PATH='C:\tools\whisper\models\ggml-small.bin'
```

Проверка: `whisper-cli.exe --help` и наличие файла модели.

## 4. Ollama и модели

```powershell
winget install Ollama.Ollama
ollama pull qwen3:1.7b
ollama pull nomic-embed-text
ollama serve
```

На слабом GPU (4 GB VRAM) рекомендуется `qwen3:1.7b` с выключенным thinking:

```bash
export OLLAMA_CHAT_MODEL=qwen3:1.7b
export OLLAMA_DISABLE_THINKING=true
```

`OLLAMA_BASE_URL` по умолчанию `http://localhost:11434`.

## 5. yt-dlp (только для ссылок)

```bash
pip install --quiet yt-dlp
yt-dlp --version
```

## 6. Каталог данных

Все данные (SQLite, медиа, артефакты, ревизии, credentials) лежат в
`LECTURE_STORAGE_ROOT` (по умолчанию `./data`). Для реальной работы задайте
отдельный диск:

```bash
export LECTURE_STORAGE_ROOT='D:/lecture-data'
```

Нужно ≥ 5–10 GB свободного места на час видео (зависит от качества и слайдов).
`/diagnostics` предупреждает, если свободного места меньше 2 GB.

## 7. Запуск

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export PATH="$JAVA_HOME/bin:/c/tools/ffmpeg/bin:/c/tools/whisper/bin:$PATH"
export LECTURE_STORAGE_ROOT='D:/lecture-data'
export LP_ADMIN_TOKEN='<локальный админ-токен>'   # для /settings и /diagnostics
./mvnw -pl backend spring-boot:run
```

Откройте `http://localhost:8080/`.

## 8. Проверка установки

1. `GET /diagnostics` — все обязательные проверки должны быть `OK`.
2. `GET /settings` — задайте провайдеров и модели по задачам (при необходимости
   добавьте DeepSeek API key; ключ шифруется AES-256-GCM).
3. Загрузите короткую тестовую лекцию, дождитесь `COMPLETED`.
4. Откройте `Редактор`, измените перевод, нажмите `Russian DOCX`.

## 9. Сборка и тесты

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
./mvnw verify
```

Внешние инструменты для тестов не нужны: они подменяются test doubles.
DeepSeek в автотестах не вызывается.

## 10. Резервное копирование

Остановите приложение и скопируйте весь `LECTURE_STORAGE_ROOT`. База SQLite и
все артефакты (включая ревизии редактора и экспорт) лежат внутри.

## Частые проблемы

| Симптом | Причина / решение |
|---------|-------------------|
| `JAVA_HOME` указывает на несуществующий путь | задайте явно, как выше |
| `./mvnw verify` падает с lock на `.jar` | остановите запущенное приложение |
| Whisper не стартует | проверьте `WHISPER_PATH`, `WHISPER_MODEL_PATH`, `--help` |
| yt-dlp: «ffmpeg is not installed» | добавьте FFmpeg в `PATH` |
| Ollama недоступна | `ollama serve`; проверьте `/diagnostics` |
| Модель обрезает конспект | уменьшите `numPredict`/чанки или используйте `qwen3:1.7b` |
| Кириллица ломается в консоли | используйте `PYTHONIOENCODING=utf-8`, читайте файлы в UTF-8 |
