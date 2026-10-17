package com.lectureprocessor.document;

import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Transcript;

/**
 * Builds human-readable Markdown documents from transcript and notes.
 */
@Component
public class MarkdownBuilder {

    public String rawTranscriptMarkdown(Lecture lecture, Transcript transcript) {
        return transcriptMarkdown(lecture, transcript, "Расшифровка лекции", "Расшифровка");
    }

    /** Transcript document with an explicit document title and section label. */
    public String transcriptMarkdown(Lecture lecture, Transcript transcript, String title,
            String section) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("**Файл:** ").append(lecture.originalFilename()).append("\n\n");
        md.append("## ").append(section).append("\n\n");
        for (var segment : transcript.segments()) {
            md.append("**[").append(Transcript.formatTimestamp(segment.startMs())).append("]** ")
                    .append(segment.text())
                    .append("\n\n");
        }
        return md.toString().strip();
    }

    /** Bilingual transcript: original and translation in the same timestamped block. */
    public String bilingualTranscriptMarkdown(Lecture lecture, Transcript original,
            java.util.List<com.lectureprocessor.multilingual.TranslationSegment> translated) {
        java.util.Map<Integer, String> bySequence = new java.util.HashMap<>();
        translated.forEach(segment -> bySequence.put(segment.sequence(), segment.translatedText()));
        StringBuilder md = new StringBuilder();
        md.append("# Расшифровка лекции (оригинал + перевод)\n\n");
        md.append("**Файл:** ").append(lecture.originalFilename()).append("\n\n");
        for (var segment : original.segments()) {
            md.append("**[").append(Transcript.formatTimestamp(segment.startMs())).append("]**\n\n");
            md.append("*EN:* ").append(segment.text()).append("\n\n");
            String ru = bySequence.get(segment.sequence());
            md.append("*RU:* ").append(ru == null || ru.isBlank() ? segment.text() : ru)
                    .append("\n\n");
        }
        return md.toString().strip();
    }
}
