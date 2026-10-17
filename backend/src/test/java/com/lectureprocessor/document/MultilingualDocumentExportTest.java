package com.lectureprocessor.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.multilingual.SegmentTranslationStatus;
import com.lectureprocessor.multilingual.TranslationSegment;

class MultilingualDocumentExportTest {

    @TempDir
    Path tempDir;

    private static Lecture lecture() {
        Instant now = Instant.now();
        return new Lecture("l1", "lecture.mp4", "source.mp4", 10, "video/mp4",
                com.lectureprocessor.domain.JobStatus.COMPLETED, null, 10_000L, false, false, null,
                null, null, null, now, now);
    }

    private static Transcript transcript() {
        return new Transcript(List.of(
                new TranscriptSegment(0, 0, 5000, "Embeddings and retrieval."),
                new TranscriptSegment(1, 5000, 10_000, "Fine-tuning a model.")), "{}");
    }

    private static List<TranslationSegment> translated() {
        return List.of(
                new TranslationSegment(0, 0, 5000, "Embeddings and retrieval.",
                        "Эмбеддинги и поиск релевантной информации.",
                        SegmentTranslationStatus.TRANSLATED, "OK", null, null, 1),
                new TranslationSegment(1, 5000, 10_000, "Fine-tuning a model.",
                        "Дообучение модели.", SegmentTranslationStatus.TRANSLATED, "OK", null,
                        null, 1));
    }

    @Test
    void bilingualMarkdownContainsBothLanguagesAndTimestamps() {
        String markdown = new MarkdownBuilder().bilingualTranscriptMarkdown(lecture(),
                transcript(), translated());

        assertThat(markdown).contains("[00:00:00]", "[00:00:05]");
        assertThat(markdown).contains("*EN:* Embeddings and retrieval.");
        assertThat(markdown).contains("*RU:* Эмбеддинги и поиск релевантной информации.");
    }

    @Test
    void bilingualAndOriginalDocxEmbedSlides() throws Exception {
        Path image = tempDir.resolve("slide.jpg");
        BufferedImage buffered = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = buffered.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 320, 200);
        graphics.setColor(Color.BLACK);
        graphics.drawString("SLIDE", 40, 100);
        graphics.dispose();
        ImageIO.write(buffered, "jpg", image.toFile());
        List<SlideImage> slides = List.of(new SlideImage(0, image, "Slide"));

        Path bilingual = tempDir.resolve("bilingual.docx");
        new DocxExporter().exportBilingual(bilingual, "Bilingual",
                List.of(new DocxExporter.BilingualLine(0, 5000, "Embeddings and retrieval.",
                        "Эмбеддинги и поиск релевантной информации.")), slides);

        Path original = tempDir.resolve("original.docx");
        new DocxExporter().exportTranscript(original, "Original", transcript().segments(), slides,
                "Язык оригинала: en");

        assertThat(Files.size(bilingual)).isPositive();
        assertThat(Files.size(original)).isPositive();
        assertThat(documentXml(bilingual)).contains("Эмбеддинги")
                .contains("Embeddings and retrieval");
        assertThat(documentXml(original)).contains("Embeddings and retrieval");
        assertThat(hasMedia(bilingual)).isTrue();
        assertThat(hasMedia(original)).isTrue();
    }

    private static String documentXml(Path docx) throws Exception {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            var entry = zip.getEntry("word/document.xml");
            return new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean hasMedia(Path docx) throws Exception {
        try (ZipFile zip = new ZipFile(docx.toFile())) {
            return zip.stream().anyMatch(e -> e.getName().startsWith("word/media/"));
        }
    }
}
