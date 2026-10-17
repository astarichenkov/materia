# Демо: мультиязычная обработка и поиск видео

Milestone Week 8. Требуется запущенный Ollama (`qwen3:1.7b`),
`nomic-embed-text`, FFmpeg, whisper.cpp и yt-dlp.

## Подготовка

```bash
export JAVA_HOME=/c/tools/jdk-25.0.2-full
export PATH="$JAVA_HOME/bin:/c/tools/ffmpeg/ffmpeg-9.0.2-essentials_build/bin:/c/tools/whisper/bin/Release:$PATH"
export WHISPER_MODEL_PATH='C:\tools\whisper\models\ggml-small.bin'
export OLLAMA_CHAT_MODEL=qwen3:1.7b OLLAMA_DISABLE_THINKING=true
export LECTURE_STORAGE_ROOT='C:/Projects/lecture-processor/target/demo-week8'
export SERVER_PORT=18080 ASSISTANT_AUTO_INDEX=false
./mvnw -pl backend spring-boot:run
```

Открыть `http://127.0.0.1:18080/`.

## 1. Английская лекция → русский конспект

1. Вставить публичный URL англоязычной лекции (YouTube/Rutube/VK).
2. Язык источника: **Auto**, перевод: **Русский**, авто-перевод включён.
3. Нажать «Скачать и обработать».
4. На карточке лекции показать:
   * определённый язык `en` и источник `WHISPER_DETECTED`;
   * статус перевода `COMPLETED`, число сегментов и модель;
   * предупреждения качества (если есть) — сегменты не теряются;
   * документы: оригинал, русскую расшифровку, двуязычную расшифровку,
     `notes.ru.docx`, `notes.bilingual.docx`.
5. Открыть русский DOCX и показать русский текст; открыть
   `transcript.original.md` — английский оригинал не изменён.

Контрольные точки для показа:

```bash
sqlite3 "$LECTURE_STORAGE_ROOT/lecture-processor.db" \
  "select status,source_language,target_language,segments_translated,segments_failed \
   from lecture_translation order by id desc limit 1;"
```

## 2. Возобновление после сбоя переводчика

1. Остановить Ollama во время перевода (или использовать прокси-тест
   `target/e2e-week8b/ollama_fail_proxy.py`, который возвращает 500 на один
   сегмент).
2. Задача завершится `PARTIAL`: часть сегментов переведена, проблемный помечен
   `FAILED`, оригинал и документы сохранены.
3. Запустить Ollama снова и нажать «Возобновить».
4. Показать, что:
   * скачивание и Whisper не повторялись (checkpoint `finished_at` не изменился);
   * переводятся только отсутствующие сегменты;
   * после завершения появляются все варианты DOCX.

## 3. Похожие лекции

1. Обработать 2–3 тематически близкие лекции (например, про machine learning)
   и одну нерелевантную.
2. Открыть любую лекцию → блок «Похожие лекции в библиотеке».
3. Показать реальный cosine score и общие темы; текущая лекция отсутствует.
4. Открыть `/discover?lectureId=…` — тот же рейтинг.

## 4. Онлайн-поиск

1. Открыть `/discover`.
2. Выбрать лекцию или ввести тему, платформу Rutube (или YouTube), нажать
   «Найти».
3. Показать реальные названия, ссылки, платформу, длительность и релевантность
   (basis `EMBEDDING_TITLE_DESCRIPTION`).
4. Повторить тот же запрос — результат должен прийти из кэша (быстрее,
   `cached`).
5. Показать блок «Провайдеры»: VK недоступен без токена — это явное
   ограничение, а не фиктивные результаты.

## 5. Добавление найденного видео

1. Нажать «Добавить в библиотеку» у найденного ролика.
2. Убедиться, что используется обычный resumable pipeline и создаётся лекция.
3. Повторно нажать на тот же ролик — приложение вернёт существующую лекцию
   (`created:false`) и не создаст дубликат.

## 6. Настройки

* `/settings` → «Lecture Translation»: отдельная модель, temperature,
  max tokens, thinking.
* «Глоссарий перевода»: добавить/отключить термин.
* «Smart Video Discovery»: YouTube API key, VK access token (шифруются).
* Предупреждение о передаче текста в облако при выборе DeepSeek.

## Ограничения для демонстрации

* YouTube search работает через yt-dlp, но скачивание на этой сети может
  блокироваться bot-check. Для demo import использовать Rutube.
* VK поиск требует user access token.
* Rutube search API публичный, но не версионированный партнёрский API.
* Локальная `qwen3:1.7b` иногда оставляет короткий сегмент непереведённым;
  он помечается предупреждением и не теряется.
