package com.lectureprocessor.web.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import com.lectureprocessor.domain.JobStatus;

/**
 * Presentation labels for job statuses. Backend states are unchanged; this is
 * only used by filter controls where a badge fragment cannot be embedded.
 */
public final class StatusLabels {

    private static final Map<String, String> LABELS = labels();

    private StatusLabels() {
    }

    public static Map<String, String> all() {
        return LABELS;
    }

    public static String label(String status) {
        return status == null ? "" : LABELS.getOrDefault(status, status);
    }

    private static Map<String, String> labels() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(JobStatus.UPLOADED.name(), "В очереди");
        values.put(JobStatus.DOWNLOADING.name(), "Скачивание");
        values.put(JobStatus.VALIDATING.name(), "Проверка файла");
        values.put(JobStatus.EXTRACTING_AUDIO.name(), "Подготовка аудио");
        values.put(JobStatus.TRANSCRIBING.name(), "Распознавание речи");
        values.put(JobStatus.TRANSLATING.name(), "Перевод");
        values.put(JobStatus.PROCESSING_TEXT.name(), "Составление конспекта");
        values.put(JobStatus.EXTRACTING_SLIDES.name(), "Извлечение слайдов");
        values.put(JobStatus.EXPORTING.name(), "Сохранение документов");
        values.put(JobStatus.COMPLETED.name(), "Готово");
        values.put(JobStatus.PARTIAL.name(), "Готово частично");
        values.put(JobStatus.INTERRUPTED.name(), "Прервано");
        values.put(JobStatus.CANCELLED.name(), "Отменено");
        values.put(JobStatus.FAILED.name(), "Ошибка");
        return Map.copyOf(values);
    }
}
