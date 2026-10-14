package com.lectureprocessor.text;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.LlmOptions;
import com.lectureprocessor.llm.LlmProvider;

/**
 * Turns a raw transcript into cleaned text and structured educational notes by
 * processing bounded chunks through the local LLM. Results are validated before
 * documents are produced.
 */
@Service
public class TextProcessingService {

    private static final Logger log = LoggerFactory.getLogger(TextProcessingService.class);
    static final int SUMMARY_INPUT_LIMIT_CHARS = 6000;

    private final LlmProvider llm;
    private final TextChunker chunker;
    private final PromptTemplates prompts;
    private final AppProperties properties;

    public TextProcessingService(LlmProvider llm, TextChunker chunker, PromptTemplates prompts,
            AppProperties properties) {
        this.llm = llm;
        this.chunker = chunker;
        this.prompts = prompts;
        this.properties = properties;
    }

    /** Mode 1: conservative ASR cleanup, preserving meaning and terminology. */
    public String cleanTranscript(List<TranscriptSegment> segments, LlmOptions options) {
        return cleanTranscript(segments, options, PromptTemplates.CLEAN_TRANSCRIPT);
    }

    /** Mode 1 with an explicit prompt template (used by recommended settings). */
    public String cleanTranscript(List<TranscriptSegment> segments, LlmOptions options, String promptTemplate) {
        List<SegmentChunk> chunks = chunksOf(segments);
        StringBuilder result = new StringBuilder();
        for (SegmentChunk chunk : chunks) {
            String cleaned = llm.chat(prompts.get(promptTemplate), chunk.text(), options);
            result.append(validate(cleaned, "очистку транскрипта")).append("\n\n");
        }
        return result.toString().strip();
    }

    /** Mode 2: structured notes with headings, definitions and a summary. */
    public StructuredNotes generateStructuredNotes(List<TranscriptSegment> segments, LlmOptions options) {
        return generateStructuredNotes(segments, options, PromptTemplates.STRUCTURED_NOTES);
    }

    /** Mode 2 with an explicit prompt template (used by recommended settings). */
    public StructuredNotes generateStructuredNotes(List<TranscriptSegment> segments, LlmOptions options,
            String promptTemplate) {
        List<SegmentChunk> chunks = chunksOf(segments);
        List<NoteSection> sections = new ArrayList<>();
        int index = 0;
        for (SegmentChunk chunk : chunks) {
            String user = "Фрагмент " + (++index) + " из " + chunks.size() + ":\n\n" + chunk.text();
            String part = llm.chat(prompts.get(promptTemplate), user, options);
            sections.add(new NoteSection(index, chunk.startMs(), chunk.endMs(),
                    validate(part, "структурирование конспекта")));
        }
        String summary = summarize(sections.stream().map(NoteSection::markdown).reduce("", (a, b) -> a + "\n\n" + b),
                options);
        String markdown = buildMarkdown(summary, sections);
        return new StructuredNotes(markdown, summary, sections);
    }

    static String buildMarkdown(String summary, List<NoteSection> sections) {
        StringBuilder document = new StringBuilder("# Конспект лекции\n\n");
        if (summary != null && !summary.isBlank()) {
            document.append("## Резюме\n\n").append(summary).append("\n\n");
        }
        document.append(String.join("\n\n", sections.stream().map(NoteSection::markdown).toList()));
        return document.toString().strip();
    }

    /** Short summary over already-produced material, bounded to a safe size. */
    public String summarize(String material, LlmOptions options) {
        if (material == null || material.isBlank()) {
            return "";
        }
        String bounded = material.length() > SUMMARY_INPUT_LIMIT_CHARS
                ? material.substring(0, SUMMARY_INPUT_LIMIT_CHARS)
                : material;
        String summary = llm.chat(prompts.get(PromptTemplates.SUMMARY), bounded, options);
        return validate(summary, "резюме");
    }

    private List<SegmentChunk> chunksOf(List<TranscriptSegment> segments) {
        int maxChars = properties.processing().llmChunkChars();
        int overlap = properties.processing().llmChunkOverlapChars();
        List<SegmentChunk> chunks = chunker.chunkSegments(segments, maxChars, overlap);
        int limit = properties.processing().maxChunksPerLecture();
        if (chunks.size() > limit) {
            log.warn("Lecture has {} chunks, truncating to configured limit {}", chunks.size(), limit);
            chunks = chunks.subList(0, limit);
        }
        return chunks;
    }

    static String validate(String output, String operation) {
        if (output == null || output.isBlank()) {
            throw new ProcessingException("LLM вернула пустой результат для операции: " + operation);
        }
        String stripped = output.strip();
        if (stripped.length() < 2) {
            throw new ProcessingException("LLM вернула некорректный результат для операции: " + operation);
        }
        return stripped;
    }
}
