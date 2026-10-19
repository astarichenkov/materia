package com.lectureprocessor.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.lectureprocessor.domain.TranscriptSegment;
import com.lectureprocessor.text.ProcessingException;

/**
 * Scenario-specific quality checks of a generated document.
 *
 * <p>These are heuristics, not proof of correctness. They never silently discard
 * a result: warnings are stored in the quality report and shown to the user. A
 * completely empty result is a hard failure, because nothing useful was
 * produced.</p>
 */
@Service
public class ScenarioOutputValidator {

    private static final Pattern HEADING = Pattern.compile("^#{1,4}\\s+(.+?)\\s*$");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final List<String> LEAK_MARKERS = List.of(
            "правила достоверности", "формат результата:", "системная инструкция",
            "верни только markdown", "не отвечай на вопросы");
    private static final List<String> ACADEMIC_HEADINGS = List.of(
            "определения", "контрольные вопросы", "вопросы для самопроверки",
            "вопросы и ответы", "классификации");

    public List<String> validate(ProcessingScenario scenario, String markdown,
            List<TranscriptSegment> segments) {
        if (markdown == null || markdown.isBlank()) {
            throw new ProcessingException("Модель не вернула содержимое для сценария «"
                    + (scenario == null ? "результат" : scenario.title()) + "»");
        }
        List<String> warnings = new ArrayList<>();
        String source = join(segments);
        String lower = markdown.toLowerCase(Locale.ROOT);

        for (String marker : LEAK_MARKERS) {
            if (lower.contains(marker)) {
                warnings.add("В результате найдено эхо служебной инструкции (" + marker
                        + "). Проверьте документ перед публикацией.");
                break;
            }
        }
        Map<String, Integer> headings = new LinkedHashMap<>();
        for (String line : markdown.split("\\R")) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                headings.merge(matcher.group(1).strip().toLowerCase(Locale.ROOT), 1,
                        Integer::sum);
            }
        }
        boolean duplicates = headings.values().stream().anyMatch(count -> count > 1);
        if (scenario != null && scenario != ProcessingScenario.DIALOGUE && duplicates) {
            warnings.add("В документе повторяются заголовки разделов. Возможно, модель "
                    + "повторила структуру в соседних фрагментах.");
        }

        if (scenario == ProcessingScenario.RECIPE) {
            validateRecipe(markdown, lower, source, warnings);
        } else if (scenario == ProcessingScenario.SHORT_SUMMARY
                || scenario == ProcessingScenario.DIALOGUE) {
            warnAcademicStructure(markdown, lower, source, warnings);
        } else if (scenario == ProcessingScenario.INSTRUCTION) {
            if (!lower.contains("шаг")) {
                warnings.add("В результате нет явной последовательности шагов — проверьте, "
                        + "действительно ли материал содержит инструкцию.");
            }
        }
        if (scenario == ProcessingScenario.DIALOGUE && segments != null
                && segments.size() >= 8 && lineCount(markdown) < 3) {
            warnings.add("Возможно, потеряны реплики: документ значительно короче источника.");
        }

        if (source.length() > 1500 && markdown.length() < source.length() / 12) {
            warnings.add("Результат существенно короче исходного материала. Проверьте, "
                    + "не был ли ответ модели обрезан.");
        }
        return warnings;
    }

    private void validateRecipe(String markdown, String lower, String source,
            List<String> warnings) {
        if (!lower.contains("ингредиент")) {
            warnings.add("В результате нет раздела с ингредиентами. Проверьте, что материал "
                    + "действительно содержит рецепт.");
        }
        Set<String> sourceNumbers = numbers(source);
        Set<String> resultNumbers = numbers(markdown);
        if (sourceNumbers.size() >= 3 && !resultNumbers.isEmpty()) {
            List<String> missing = sourceNumbers.stream()
                    .filter(number -> !resultNumbers.contains(number))
                    .toList();
            double missingRatio = (double) missing.size() / sourceNumbers.size();
            if (missingRatio > 0.4) {
                warnings.add("В рецепте потеряна часть количеств из материала ("
                        + missing.size() + " из " + sourceNumbers.size()
                        + "). Не добавляйте количества самостоятельно — сверьтесь с источником.");
            }
        } else if (sourceNumbers.isEmpty() && lower.contains("ингредиент")) {
            warnings.add("Количества ингредиентов не названы в материале — в рецепте должно быть "
                    + "указано «количество не указано».");
        }
    }

    private void warnAcademicStructure(String markdown, String lower, String source,
            List<String> warnings) {
        String sourceLower = source.toLowerCase(Locale.ROOT);
        for (String heading : ACADEMIC_HEADINGS) {
            if (lower.contains(heading) && !sourceLower.contains(heading)) {
                warnings.add("В результате появился академический раздел «" + heading
                        + "», которого нет в источнике. Проверьте, не навязана ли структура конспекта.");
                return;
            }
        }
    }

    private static Set<String> numbers(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.add(matcher.group().replace(',', '.'));
        }
        return result;
    }

    private static int lineCount(String markdown) {
        return (int) markdown.lines().filter(line -> !line.isBlank()).count();
    }

    private static String join(List<TranscriptSegment> segments) {
        if (segments == null) {
            return "";
        }
        return segments.stream().map(TranscriptSegment::text)
                .reduce("", (a, b) -> a + " " + b);
    }
}
