package com.lectureprocessor.diagnostics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Read-only installation diagnostics. Every check reports a concrete status and
 * an actionable hint; it never tries to install or modify anything.
 */
@Service
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);
    private static final long DISK_WARNING_BYTES = 2L * 1024 * 1024 * 1024;

    private final AppProperties properties;
    private final FileStorageService storage;
    private final JdbcTemplate jdbc;

    public DiagnosticsService(AppProperties properties, FileStorageService storage,
            JdbcTemplate jdbc) {
        this.properties = properties;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public DiagnosticsReport report() {
        List<Check> checks = new ArrayList<>();
        checks.add(checkJava());
        checks.add(checkStorage());
        checks.add(checkTemp());
        checks.add(checkDatabase());
        checks.add(checkExecutable("ffmpeg", "FFmpeg", properties.tools().ffmpegPath(),
                "Установите FFmpeg и задайте FFMPEG_PATH (например C:\\tools\\ffmpeg\\bin)."));
        checks.add(checkExecutable("ffprobe", "FFprobe", properties.tools().ffprobePath(),
                "FFprobe поставляется вместе с FFmpeg; задайте FFPROBE_PATH."));
        checks.add(checkWhisper());
        checks.add(checkExecutable("yt-dlp", "yt-dlp", properties.downloader().ytDlpPath(),
                "pip install yt-dlp (или задайте YTDLP_PATH)."));
        checks.add(checkOllama());
        return new DiagnosticsReport(checks, checks.stream().noneMatch(c -> c.status() == Status.FAILED),
                checks.stream().anyMatch(c -> c.status() != Status.OK), java.time.Instant.now());
    }

    private Check checkJava() {
        String version = System.getProperty("java.version", "unknown");
        int major = parseMajor(version);
        Status status = major >= 21 ? Status.OK : Status.FAILED;
        return new Check("java", "Java runtime", status, "Java " + version + " ("
                + System.getProperty("java.vendor", "?") + ")",
                status == Status.OK ? null : "Требуется Java 21+, рекомендуется Java 25 (Temurin).");
    }

    private Check checkStorage() {
        Path root = storage.root();
        try {
            Files.createDirectories(root);
            Path probe = Files.createTempFile(root, ".diag-", ".tmp");
            Files.deleteIfExists(probe);
            FileStore store = Files.getFileStore(root);
            long usable = store.getUsableSpace();
            Status status = usable < DISK_WARNING_BYTES ? Status.WARNING : Status.OK;
            return new Check("storage", "Каталог данных", status,
                    root + " (свободно " + formatBytes(usable) + ")",
                    status == Status.OK ? null
                            : "Мало свободного места: удалите старые лекции или перенесите "
                                    + "LECTURE_STORAGE_ROOT на другой диск.");
        } catch (IOException e) {
            return new Check("storage", "Каталог данных", Status.FAILED, root + ": " + e.getMessage(),
                    "Каталог недоступен для записи. Задайте LECTURE_STORAGE_ROOT на диск с правами "
                            + "записи.");
        }
    }

    private Check checkTemp() {
        try {
            Path temp = Path.of(System.getProperty("java.io.tmpdir", "."));
            Path probe = Files.createTempFile(temp, ".diag-", ".tmp");
            Files.deleteIfExists(probe);
            return new Check("temp", "Временный каталог", Status.OK, temp.toString(), null);
        } catch (IOException e) {
            return new Check("temp", "Временный каталог", Status.FAILED, e.getMessage(),
                    "Проверьте права на %TEMP% / /tmp.");
        }
    }

    private Check checkDatabase() {
        try {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            return new Check("sqlite", "SQLite", value != null && value == 1
                    ? Status.OK : Status.WARNING, "Соединение установлено", null);
        } catch (RuntimeException e) {
            return new Check("sqlite", "SQLite", Status.FAILED, e.getMessage(),
                    "Проверьте каталог данных и права на файл lecture-processor.db.");
        }
    }

    private Check checkWhisper() {
        String whisper = properties.tools().whisperPath();
        String model = properties.tools().whisperModelPath();
        List<String> problems = new ArrayList<>();
        if (!runs(whisper, "--help")) {
            problems.add("whisper-cli не запускается по пути " + whisper);
        }
        if (model == null || model.isBlank() || !Files.isRegularFile(Path.of(model))) {
            problems.add("модель не найдена: " + (model == null ? "WHISPER_MODEL_PATH не задан" : model));
        }
        if (problems.isEmpty()) {
            return new Check("whisper", "Whisper.cpp", Status.OK,
                    whisper + ", модель " + model, null);
        }
        return new Check("whisper", "Whisper.cpp", Status.FAILED, String.join("; ", problems),
                "Установите whisper.cpp (WHISPER_PATH) и модель ggml (WHISPER_MODEL_PATH).");
    }

    private Check checkOllama() {
        String base = properties.ollama().baseUrl();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(base) + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Check("ollama", "Ollama", Status.WARNING,
                        "HTTP " + response.statusCode() + " от " + base,
                        "Ollama отвечает ошибкой. Проверьте версию и модель.");
            }
            String body = response.body() == null ? "" : response.body();
            String chat = properties.ollama().chatModel();
            String embedding = properties.ollama().embeddingModel();
            boolean chatPresent = modelPresent(body, chat);
            boolean embeddingPresent = modelPresent(body, embedding);
            if (chatPresent && embeddingPresent) {
                return new Check("ollama", "Ollama", Status.OK,
                        base + " (модели " + chat + ", " + embedding + " загружены)", null);
            }
            List<String> missing = new ArrayList<>();
            if (!chatPresent) {
                missing.add(chat);
            }
            if (!embeddingPresent) {
                missing.add(embedding);
            }
            return new Check("ollama", "Ollama", Status.WARNING,
                    base + " (не найдены модели: " + String.join(", ", missing) + ")",
                    "Выполните ollama pull " + String.join(" && ollama pull ", missing));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Check("ollama", "Ollama", Status.WARNING,
                    "Ollama недоступна по " + base,
                    "Запустите ollama serve или задайте OLLAMA_BASE_URL. Без Ollama работают "
                            + "только уже готовые артефакты и повторный экспорт.");
        }
    }

    private Check checkExecutable(String id, String label, String command, String hint) {
        if (command == null || command.isBlank()) {
            return new Check(id, label, Status.FAILED, "путь не задан", hint);
        }
        if (runs(command, id.equals("yt-dlp") ? "--version" : "-version")) {
            return new Check(id, label, Status.OK, "Найден: " + command, null);
        }
        return new Check(id, label, Status.FAILED, "Не запускается: " + command, hint);
    }

    private static boolean runs(String command, String argument) {
        try {
            Process process = new ProcessBuilder(command, argument)
                    .redirectErrorStream(true).start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Matches a model name with or without an explicit {@code :latest} tag. */
    private static boolean modelPresent(String body, String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        return body.contains("\"" + model + "\"")
                || body.contains("\"" + model + ":");
    }

    private static String trimSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static int parseMajor(String version) {
        try {
            String[] parts = version.split("[.+-]");
            return Integer.parseInt(parts[0]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String formatBytes(long bytes) {
        double value = bytes;
        for (String unit : List.of("B", "KB", "MB", "GB", "TB")) {
            if (value < 1024 || "TB".equals(unit)) {
                return String.format(Locale.ROOT, "%.1f %s", value, unit);
            }
            value /= 1024;
        }
        return bytes + " B";
    }

    public enum Status { OK, WARNING, FAILED }

    public record Check(String id, String label, Status status, String detail, String hint) {
    }

    public record DiagnosticsReport(List<Check> checks, boolean ready, boolean hasWarnings,
            java.time.Instant checkedAt) {
    }
}
