package com.lectureprocessor.document;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.text.NoteSection;
import com.lectureprocessor.text.StructuredNotes;

/**
 * Generates a DOCX from structured notes and inserts slide images near the
 * notes section whose time span contains the slide timestamp. Slides that
 * cannot be aligned are appended chronologically.
 */
@Component
public class DocxExporter {

    private static final int MAX_IMAGE_WIDTH_PX = 600;
    private static final int MAX_IMAGE_HEIGHT_PX = 400;

    public void export(Path output, String title, StructuredNotes notes, List<SlideImage> slides) {
        List<SlideImage> pending = new ArrayList<>(slides);
        try (XWPFDocument document = new XWPFDocument()) {
            addHeading(document, title, 0);
            if (notes.summary() != null && !notes.summary().isBlank()) {
                addHeading(document, "Резюме", 1);
                addParagraph(document, notes.summary());
            }
            for (NoteSection section : notes.sections()) {
                List<SlideImage> matching = pending.stream()
                        .filter(s -> s.timestampMs() >= section.startMs() && s.timestampMs() <= section.endMs())
                        .toList();
                pending.removeAll(matching);
                writeMarkdown(document, section.markdown());
                matching.forEach(slide -> insertImage(document, slide));
            }
            if (!pending.isEmpty()) {
                addHeading(document, "Приложение: слайды", 1);
                pending.forEach(slide -> insertImage(document, slide));
            }
            write(document, output);
        } catch (IOException e) {
            throw new DocumentExportException("Не удалось создать DOCX: " + output, e);
        }
    }

    /**
     * Exports a timestamped transcript as a document, inserting slides near the
     * segments whose time span contains the slide timestamp.
     */
    public void exportTranscript(Path output, String title, List<TranscriptSegment> segments,
            List<SlideImage> slides, String languageLabel) {
        List<SlideImage> pending = new ArrayList<>(slides);
        try (XWPFDocument document = new XWPFDocument()) {
            addHeading(document, title, 0);
            if (languageLabel != null && !languageLabel.isBlank()) {
                addParagraph(document, languageLabel);
            }
            for (TranscriptSegment segment : segments) {
                List<SlideImage> matching = pending.stream()
                        .filter(s -> s.timestampMs() >= segment.startMs()
                                && s.timestampMs() <= Math.max(segment.endMs(), segment.startMs() + 1))
                        .toList();
                pending.removeAll(matching);
                matching.forEach(slide -> insertImage(document, slide));
                addParagraph(document, "[" + Transcript.formatTimestamp(segment.startMs()) + "] "
                        + segment.text());
            }
            if (!pending.isEmpty()) {
                addHeading(document, "Приложение: слайды", 1);
                pending.forEach(slide -> insertImage(document, slide));
            }
            write(document, output);
        } catch (IOException e) {
            throw new DocumentExportException("Не удалось создать DOCX: " + output, e);
        }
    }

    /** Exports a bilingual document: original and translation blocks per segment. */
    public void exportBilingual(Path output, String title, List<BilingualLine> lines,
            List<SlideImage> slides) {
        List<SlideImage> pending = new ArrayList<>(slides);
        try (XWPFDocument document = new XWPFDocument()) {
            addHeading(document, title, 0);
            for (BilingualLine line : lines) {
                List<SlideImage> matching = pending.stream()
                        .filter(s -> s.timestampMs() >= line.startMs()
                                && s.timestampMs() <= Math.max(line.endMs(), line.startMs() + 1))
                        .toList();
                pending.removeAll(matching);
                matching.forEach(slide -> insertImage(document, slide));
                addHeading(document, "[" + Transcript.formatTimestamp(line.startMs()) + "]", 3);
                XWPFParagraph original = document.createParagraph();
                XWPFRun originalRun = original.createRun();
                originalRun.setItalic(true);
                originalRun.setText("EN: " + line.original());
                XWPFParagraph translated = document.createParagraph();
                XWPFRun translatedRun = translated.createRun();
                translatedRun.setText("RU: " + line.translated());
            }
            if (!pending.isEmpty()) {
                addHeading(document, "Приложение: слайды", 1);
                pending.forEach(slide -> insertImage(document, slide));
            }
            write(document, output);
        } catch (IOException e) {
            throw new DocumentExportException("Не удалось создать DOCX: " + output, e);
        }
    }

    private static void write(XWPFDocument document, Path output) {
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var out = Files.newOutputStream(output)) {
                document.write(out);
            }
        } catch (IOException e) {
            throw new DocumentExportException("Не удалось создать DOCX: " + output, e);
        }
    }

    /**
     * One bilingual line: source and translated text with the original timeline.
     */
    public record BilingualLine(long startMs, long endMs, String original, String translated) {
    }

    private static void writeMarkdown(XWPFDocument document, String markdown) {
        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("### ")) {
                addHeading(document, line.substring(4), 3);
            } else if (line.startsWith("## ")) {
                addHeading(document, line.substring(3), 2);
            } else if (line.startsWith("# ")) {
                addHeading(document, line.substring(2), 1);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                addParagraph(document, "• " + stripEmphasis(line.substring(2)));
            } else {
                addParagraph(document, stripEmphasis(line));
            }
        }
    }

    private static String stripEmphasis(String text) {
        return text.replace("**", "").replace("__", "").replace("`", "").strip();
    }

    private static void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        if (level > 0) {
            paragraph.setStyle("Heading" + Math.min(level, 3));
        }
        XWPFRun run = paragraph.createRun();
        run.setBold(level == 0);
        run.setFontSize(level == 0 ? 20 : 14);
        run.setText(text);
    }

    private static void addParagraph(XWPFDocument document, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
    }

    private static void insertImage(XWPFDocument document, SlideImage slide) {
        if (slide.imagePath() == null || !Files.exists(slide.imagePath())) {
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = paragraph.createRun();
        try (InputStream in = Files.newInputStream(slide.imagePath())) {
            int[] size = scaledSize(slide.imagePath());
            run.addPicture(in, XWPFDocument.PICTURE_TYPE_JPEG, slide.imagePath().getFileName().toString(),
                    Units.toEMU(size[0]), Units.toEMU(size[1]));
        } catch (Exception e) {
            throw new DocumentExportException("Не удалось вставить изображение: " + slide.imagePath(), e);
        }
        XWPFParagraph caption = document.createParagraph();
        caption.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun captionRun = caption.createRun();
        captionRun.setItalic(true);
        captionRun.setText("Слайд [" + Transcript.formatTimestamp(slide.timestampMs()) + "]");
    }

    /** Returns width/height in points, scaled to fit the page. */
    private static int[] scaledSize(Path image) throws IOException {
        try (InputStream in = Files.newInputStream(image)) {
            BufferedImage buffered = ImageIO.read(in);
            if (buffered == null) {
                return new int[] { MAX_IMAGE_WIDTH_PX, MAX_IMAGE_HEIGHT_PX };
            }
            double scale = Math.min((double) MAX_IMAGE_WIDTH_PX / buffered.getWidth(),
                    (double) MAX_IMAGE_HEIGHT_PX / buffered.getHeight());
            scale = Math.min(scale, 1.0);
            return new int[] {
                    (int) Math.round(buffered.getWidth() * scale),
                    (int) Math.round(buffered.getHeight() * scale)
            };
        }
    }
}
