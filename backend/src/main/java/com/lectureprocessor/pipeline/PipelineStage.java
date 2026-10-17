package com.lectureprocessor.pipeline;

/**
 * Ordered stages of the lecture processing pipeline. Persisted identifiers must
 * remain stable — they are checkpoint keys.
 */
public enum PipelineStage {
    SOURCE_RESOLUTION("Разрешение источника"),
    DOWNLOADING("Скачивание"),
    MEDIA_VALIDATION("Проверка медиа"),
    AUDIO_EXTRACTION("Извлечение аудио"),
    TRANSCRIPTION("Распознавание речи"),
    TRANSLATION("Перевод"),
    TEXT_PROCESSING("Обработка текста LLM"),
    RAG_INDEXING("Индексация RAG"),
    SLIDE_EXTRACTION("Извлечение слайдов"),
    EXPORT("Экспорт документов");

    private final String title;

    PipelineStage(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
