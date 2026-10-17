package com.lectureprocessor.multilingual;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.support.FakeLlmProvider;
import com.lectureprocessor.support.TestProperties;
import com.lectureprocessor.text.PromptTemplates;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import tools.jackson.databind.ObjectMapper;

class TranslationServiceTest {

    private Path tempDir;
    private HikariDataSource dataSource;
    private TranslationRepository repository;
    private TranslationService service;
    private FakeLlmProvider llm;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("translation-test");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("test.db").toAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/schema.sql"));
        }
        repository = new TranslationRepository(jdbc, objectMapper);
        TranslationGlossaryService glossary = new TranslationGlossaryService(
                new TranslationGlossaryRepository(jdbc), MultilingualProperties.defaults());
        service = new TranslationService(repository, glossary, new PromptTemplates(),
                new CloudCostService(TestProperties.defaults(tempDir.toString())),
                MultilingualProperties.defaults(), objectMapper);
        llm = new FakeLlmProvider();
        llm.setChatFunction((system, user) -> {
            var ids = idsOf(user);
            StringBuilder json = new StringBuilder("{\"segments\":[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"id\":").append(ids.get(i))
                        .append(",\"text\":\"Перевод ").append(ids.get(i)).append("\"}");
            }
            return json.append("]}").toString();
        });
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void translatesAllSegmentsPreservingIdsOrderAndTimestamps() {
        var outcome = service.translate(request(List.of(
                new TranscriptSegment(0, 0, 1000, "First segment"),
                new TranscriptSegment(1, 1000, 2000, "Second segment"))));

        assertThat(outcome.status()).isEqualTo(TranslationStatus.COMPLETED);
        assertThat(outcome.segments()).extracting(TranslationSegment::sequence)
                .containsExactly(0, 1);
        assertThat(outcome.segments()).extracting(TranslationSegment::startMs)
                .containsExactly(0L, 1000L);
        assertThat(outcome.segments()).allSatisfy(s -> {
            assertThat(s.translatedText()).startsWith("Перевод");
            assertThat(s.status()).isEqualTo(SegmentTranslationStatus.TRANSLATED);
        });
        assertThat(repository.findAll("l1")).hasSize(1);
        assertThat(repository.segments(repository.findAll("l1").getFirst().id())).hasSize(2);
    }

    @Test
    void retriesOnlyMissingSegmentsAfterPartialFailure() {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment(0, 0, 1000, "First segment"),
                new TranscriptSegment(1, 1000, 2000, "Second segment"),
                new TranscriptSegment(2, 2000, 3000, "Third segment"));
        llm.setChatFunction((system, user) -> {
            if (user.contains("Second segment")) {
                throw new RuntimeException("provider timeout");
            }
            return validJson(user);
        });

        var first = service.translate(request(segments));

        assertThat(first.status()).isEqualTo(TranslationStatus.PARTIAL);
        assertThat(first.translated()).isEqualTo(2);
        assertThat(first.failed()).isEqualTo(1);

        int callsAfterFirstRun = llm.chatCalls().size();
        llm.setChatFunction((system, user) -> validJson(user));
        var second = service.translate(request(segments));

        assertThat(second.status()).isEqualTo(TranslationStatus.COMPLETED);
        assertThat(second.translated()).isEqualTo(3);
        // Already translated segments were not sent to the LLM again.
        assertThat(llm.chatCalls()).hasSize(callsAfterFirstRun + 1);
        assertThat(llm.chatCalls().getLast()).contains("Second segment")
                .doesNotContain("First segment");
    }

    @Test
    void changingModelCreatesANewTranslationVersionInsteadOfMixing() {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment(0, 0, 1000, "First segment"));
        service.translate(request(segments));
        service.translate(request(segments, "other-model"));

        assertThat(repository.findAll("l1")).hasSize(2);
    }

    @Test
    void flagsGlossaryAndNumberLossAsWarningsWithoutLosingSegments() {
        llm.setChatFunction((system, user) -> """
                {"segments":[{"id":0,"text":"Здесь 42 без термина"}]}
                """);
        var outcome = service.translate(request(List.of(
                new TranscriptSegment(0, 0, 1000, "The embedding gives results"))));

        assertThat(outcome.status()).isEqualTo(TranslationStatus.COMPLETED);
        assertThat(outcome.segments().getFirst().status())
                .isEqualTo(SegmentTranslationStatus.WARNING);
        assertThat(outcome.segments().getFirst().translatedText()).contains("42");
        assertThat(outcome.segments().getFirst().warning()).contains("термин");
    }

    @Test
    void rejectsBatchesWhereDifferentSegmentsGotTheSameTranslation() {
        var segments = List.of(
                new TranslationSegment(0, 0, 1000, "First", null,
                        SegmentTranslationStatus.PENDING, "OK", null, null, 0),
                new TranslationSegment(1, 1000, 2000, "Second", null,
                        SegmentTranslationStatus.PENDING, "OK", null, null, 0));
        assertThat(TranslationService.duplicatedTranslations(segments,
                java.util.Map.of(0, "Одинаково", 1, "Одинаково"))).isTrue();
        assertThat(TranslationService.duplicatedTranslations(segments,
                java.util.Map.of(0, "Первый", 1, "Второй"))).isFalse();
    }

    @Test
    void invalidJsonAtSegmentLevelFailsOnlyThatSegment() {
        llm.setChatFunction((system, user) -> {
            if (user.contains("bad")) {
                return "not json at all";
            }
            return validJson(user);
        });
        var outcome = service.translate(request(List.of(
                new TranscriptSegment(0, 0, 1000, "good one"),
                new TranscriptSegment(1, 1000, 2000, "bad one"))));

        assertThat(outcome.status()).isEqualTo(TranslationStatus.PARTIAL);
        assertThat(outcome.segments().get(0).done()).isTrue();
        assertThat(outcome.segments().get(1).done()).isFalse();
    }

    private TranslationService.TranslationRequest request(List<TranscriptSegment> segments) {
        return request(segments, "test-model");
    }

    private TranslationService.TranslationRequest request(List<TranscriptSegment> segments,
            String model) {
        LlmRunConfig config = new LlmRunConfig("ollama", model, 0.1, 2048, 4096, false,
                PromptTemplates.LECTURE_TRANSLATION);
        ResolvedLlmTask resolved = new ResolvedLlmTask(LlmTaskType.LECTURE_TRANSLATION, config,
                llm, config.toOptions(), false, null, false);
        return new TranslationService.TranslationRequest("l1", 0L, segments, fingerprint(segments),
                "en", "ru", resolved, new CancellationToken());
    }

    private static String fingerprint(List<TranscriptSegment> segments) {
        return TranslationService.sourceFingerprint(segments);
    }

    private static List<Integer> idsOf(String userPrompt) {
        var result = new java.util.ArrayList<Integer>();
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(userPrompt);
        while (matcher.find()) {
            result.add(Integer.parseInt(matcher.group(1)));
        }
        return result;
    }

    private static String validJson(String userPrompt) {
        StringBuilder json = new StringBuilder("{\"segments\":[");
        List<Integer> ids = idsOf(userPrompt);
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(ids.get(i)).append(",\"text\":\"Перевод ")
                    .append(ids.get(i)).append("\"}");
        }
        return json.append("]}").toString();
    }
}
