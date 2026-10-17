package com.lectureprocessor.lecture;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.multilingual.LectureLanguage;
import com.lectureprocessor.multilingual.LectureLanguageRepository;
import com.lectureprocessor.multilingual.MultilingualProperties;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Application service for lecture upload, lookup and deletion, including upload
 * validation (size, extension, emptiness).
 */
@Service
public class LectureService {

    private static final Logger log = LoggerFactory.getLogger(LectureService.class);
    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of(".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v");

    private final LectureRepository repository;
    private final FileStorageService storage;
    private final AppProperties properties;
    private final LectureLanguageRepository languageRepository;
    private final MultilingualProperties multilingual;

    public LectureService(LectureRepository repository, FileStorageService storage,
            AppProperties properties, LectureLanguageRepository languageRepository,
            MultilingualProperties multilingual) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.languageRepository = languageRepository;
        this.multilingual = multilingual;
    }

    public Lecture create(MultipartFile file, boolean cleanTranscript, boolean extractSlides) {
        return create(file, cleanTranscript, extractSlides, multilingual.defaultLanguageMode(),
                multilingual.defaultTranslationMode(), multilingual.autoTranslate(),
                multilingual.targetLanguage());
    }

    /** Upload with explicit multilingual preferences. */
    public Lecture create(MultipartFile file, boolean cleanTranscript, boolean extractSlides,
            String languageMode, String translationMode, boolean autoTranslate,
            String targetLanguage) {
        validate(file);
        String id = UUID.randomUUID().toString();
        String original = sanitizeFilename(file.getOriginalFilename());
        try (InputStream in = file.getInputStream()) {
            storage.saveSource(id, original, in);
        } catch (IOException e) {
            throw new InvalidUploadException("Не удалось прочитать загруженный файл: " + e.getMessage());
        }
        String storedFilename = "source" + FileStorageService.extensionOf(original);
        Instant now = Instant.now();
        Lecture lecture = new Lecture(id, original, storedFilename, file.getSize(),
                file.getContentType(), JobStatus.UPLOADED, null, null,
                cleanTranscript, extractSlides, null, null, null, null, now, now);
        repository.save(lecture);
        languageRepository.save(LectureLanguage.defaults(id, languageMode, translationMode,
                targetLanguage, autoTranslate));
        log.info("Lecture {} uploaded: {} ({} bytes), language={}", id, original, file.getSize(),
                languageMode);
        return lecture;
    }

    public Lecture getRequired(String id) {
        return repository.findById(id).orElseThrow(() -> new LectureNotFoundException(id));
    }

    public List<Lecture> findAll() {
        return repository.findAll();
    }

    public void delete(String id) {
        repository.deleteById(id);
        storage.deleteLectureData(id);
        log.info("Lecture {} deleted", id);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("Файл не загружен или пуст");
        }
        long max = properties.upload().maxFileSizeBytes();
        if (file.getSize() > max) {
            throw new InvalidUploadException(
                    "Файл слишком большой: " + file.getSize() + " байт (максимум " + max + ")");
        }
        String extension = FileStorageService.extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidUploadException("Неподдерживаемый формат видео: " + extension);
        }
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "lecture.mp4";
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("[^a-zA-Z0-9а-яА-ЯёЁ._ -]", "_").strip();
        return cleaned.isEmpty() ? "lecture.mp4" : cleaned;
    }

    static boolean isAllowedExtension(String filename) {
        if (filename == null) {
            return false;
        }
        return ALLOWED_EXTENSIONS.contains(FileStorageService.extensionOf(filename.toLowerCase(Locale.ROOT)));
    }
}
