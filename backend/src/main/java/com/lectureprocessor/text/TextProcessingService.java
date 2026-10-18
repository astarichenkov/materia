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
        return cleanTranscript(llm, segments, options, promptTemplate);
    }

    /** Mode 1 against an explicitly resolved provider (default cleanup prompt). */
    public String cleanTranscript(LlmProvider provider, List<TranscriptSegment> segments, LlmOptions options) {
        return cleanTranscript(provider, segments, options, PromptTemplates.CLEAN_TRANSCRIPT);
    }

    /**
     * Mode 1 against an explicitly resolved provider (per-task model routing).
     * Kept separate from the default provider so lecture processing can use a
     * cloud provider without changing the embedding/local default.
     */
    public String cleanTranscript(LlmProvider provider, List<TranscriptSegment> segments, LlmOptions options,
            String promptTemplate) {
        List<SegmentChunk> chunks = chunksOf(segments);
        StringBuilder result = new StringBuilder();
        for (SegmentChunk chunk : chunks) {
            String cleaned = provider.chat(prompts.get(promptTemplate), chunk.text(), options);
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
        return generateStructuredNotes(llm, segments, options, promptTemplate);
    }

    /** Mode 2 against an explicitly resolved provider (per-task model routing). */
    public StructuredNotes generateStructuredNotes(LlmProvider provider, List<TranscriptSegment> segments,
            LlmOptions options, String promptTemplate) {
        return generateStructuredNotes(provider, segments, options, promptTemplate, null);
    }

    /**
     * Mode 2 with an extra formatting instruction (material type + detail level).
     * The instruction is appended to the user message and never overrides the
     * reliability rules of the prompt template itself.
     */
    public StructuredNotes generateStructuredNotes(LlmProvider provider, List<TranscriptSegment> segments,
            LlmOptions options, String promptTemplate, String instructions) {
        List<SegmentChunk> chunks = chunksOf(segments);
        List<NoteSection> sections = new ArrayList<>();
        int index = 0;
        for (SegmentChunk chunk : chunks) {
            String user = "Фрагмент " + (++index) + " из " + chunks.size() + ":"
                    + (instructions == null || instructions.isBlank() ? ""
                            : "\n\n" + instructions)
                    + "\n\n" + chunk.text();
            String part = provider.chat(prompts.get(promptTemplate), user, options);
            sections.add(new NoteSection(index, chunk.startMs(), chunk.endMs(),
                    validate(part, "структурирование конспекта")));
        }
        String summary = summarize(provider,
                sections.stream().map(NoteSection::markdown).reduce("", (a, b) -> a + "\n\n" + b), options);
        String markdown = buildMarkdown(summary, sections);
        return new StructuredNotes(markdown, summary, sections);
    }

    /** Cleaned transcript deliverable against the default provider. */
    public CleanedTranscriptDocument cleanTranscriptDocument(List<TranscriptSegment> segments,
            LlmOptions options, String promptTemplate) {
        return cleanTranscriptDocument(llm, segments, options, promptTemplate);
    }

    /**
     * Produces the "cleaned transcript" deliverable: the text stays close to the
     * original, is split into readable paragraphs and is not summarised. Sections
     * preserve the source time span so slides are still placed correctly.
     */
    public CleanedTranscriptDocument cleanTranscriptDocument(LlmProvider provider,
            List<TranscriptSegment> segments, LlmOptions options, String promptTemplate) {
        List<SegmentChunk> chunks = chunksOf(segments);
        List<NoteSection> sections = new ArrayList<>();
        StringBuilder cleaned = new StringBuilder();
        int index = 0;
        for (SegmentChunk chunk : chunks) {
            String part = provider.chat(prompts.get(promptTemplate), chunk.text(), options);
            String validated = validate(part, "очистку транскрипта");
            sections.add(new NoteSection(++index, chunk.startMs(), chunk.endMs(), validated));
            cleaned.append(validated).append("\n\n");
        }
        String markdown = "# Расшифровка лекции\n\n"
                + sections.stream().map(NoteSection::markdown).reduce("", (a, b) -> a + "\n\n" + b);
        return new CleanedTranscriptDocument(new StructuredNotes(markdown.strip(), "", sections),
                cleaned.toString().strip());
    }

    /** Result of the cleaned-transcript mode: the document plus the plain text. */
    public record CleanedTranscriptDocument(StructuredNotes notes, String cleanedText) {
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
        return summarize(llm, material, options);
    }

    /** Short summary against an explicitly resolved provider. */
    public String summarize(LlmProvider provider, String material, LlmOptions options) {
        if (material == null || material.isBlank()) {
            return "";
        }
        String bounded = material.length() > SUMMARY_INPUT_LIMIT_CHARS
                ? material.substring(0, SUMMARY_INPUT_LIMIT_CHARS)
                : material;
        String summary = provider.chat(prompts.get(PromptTemplates.SUMMARY), bounded, options);
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
