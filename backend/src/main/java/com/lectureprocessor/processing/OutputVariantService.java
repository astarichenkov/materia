package com.lectureprocessor.processing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.util.FileChecksum;

/**
 * Archives every produced document variant so re-generating a result never
 * destroys the previous one. Files live under
 * {@code lectures/<id>/variants/} inside the managed workspace; the database row
 * keeps the profile, prompt and model that produced it.
 */
@Service
public class OutputVariantService {

    public static final String VARIANTS_DIR = "variants";
    private static final Logger log = LoggerFactory.getLogger(OutputVariantService.class);
    private static final int LIST_LIMIT = 30;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final OutputVariantRepository repository;
    private final FileStorageService storage;

    public OutputVariantService(OutputVariantRepository repository, FileStorageService storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /**
     * Persists a new variant with its Markdown document. Returns the id or
     * {@code null} when archiving failed (the pipeline result itself is kept).
     */
    public Long archive(String lectureId, ProcessingProfile profile, LlmRunConfig config,
            String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        try {
            String prompt = profile.resolvedPrompt();
            long id = repository.insert(new OutputVariant(0, lectureId, profile.materialType(),
                    profile.outputFormat(), profile.detailLevel(), prompt,
                    config == null ? null : config.provider(),
                    config == null ? null : config.model(), markdown, null, null,
                    FileChecksum.sha256(markdown), Instant.now(), profile.scenario()));
            String relative = VARIANTS_DIR + "/" + STAMP.format(Instant.now()) + "-" + id + "-"
                    + profile.outputFormat().name().toLowerCase(java.util.Locale.ROOT) + ".md";
            write(lectureId, relative, markdown.getBytes(StandardCharsets.UTF_8));
            repository.updateArtifacts(id, relative, null);
            return id;
        } catch (RuntimeException e) {
            log.warn("Cannot archive output variant for {}: {}", lectureId, e.getMessage());
            return null;
        }
    }

    /** Copies the freshly exported DOCX into the latest variant archive. */
    public void attachExport(String lectureId, Path workingDocx) {
        if (workingDocx == null || !Files.isRegularFile(workingDocx)) {
            return;
        }
        Optional<OutputVariant> latest = repository.latest(lectureId);
        if (latest.isEmpty() || latest.get().hasDocx()) {
            return;
        }
        OutputVariant variant = latest.get();
        String relative = VARIANTS_DIR + "/" + STAMP.format(Instant.now()) + "-" + variant.id()
                + "-" + variant.outputFormat().name().toLowerCase(java.util.Locale.ROOT) + ".docx";
        try {
            Path target = storage.resolve(lectureId, relative);
            Files.createDirectories(target.getParent());
            Files.copy(workingDocx, target, StandardCopyOption.REPLACE_EXISTING);
            repository.updateArtifacts(variant.id(), null, relative);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot archive DOCX variant for {}: {}", lectureId, e.getMessage());
        }
    }

    public List<OutputVariant> list(String lectureId) {
        return repository.list(lectureId, LIST_LIMIT);
    }

    public Optional<OutputVariant> find(String lectureId, long id) {
        return repository.find(id).filter(variant -> variant.lectureId().equals(lectureId));
    }

    public Optional<OutputVariant> latest(String lectureId) {
        return repository.latest(lectureId);
    }

    public Path artifactPath(OutputVariant variant, String artifact) {
        String relative = switch (artifact) {
            case "md", "markdown" -> variant.markdownArtifact();
            case "docx" -> variant.docxArtifact();
            default -> null;
        };
        if (relative == null || relative.isBlank()) {
            return null;
        }
        Path path = storage.resolve(variant.lectureId(), relative);
        return Files.isRegularFile(path) ? path : null;
    }

    private void write(String lectureId, String relative, byte[] bytes) {
        Path target = storage.resolve(lectureId, relative);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить вариант документа", e);
        }
    }
}
