package com.lectureprocessor.fileagent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.FileAgentPlan;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.fileagent.workspace.FileSearchHit;
import com.lectureprocessor.fileagent.workspace.FileWorkspaceRef;
import com.lectureprocessor.fileagent.workspace.WorkspaceFileService;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.text.PromptTemplates;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Two-stage planning. The deterministic stage derives seed queries/symbols; the
 * LLM then selects which candidates to read and what kind of change to propose.
 * A deterministic fallback keeps the agent usable when the model is unavailable.
 */
@Service
public class FileAgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(FileAgentPlanner.class);
    private static final Pattern IDENTIFIER = Pattern.compile("\\b[A-Z][A-Za-z0-9]{2,}\\b");
    private static final Pattern BACKTICK = Pattern.compile("`([^`]{2,80})`");
    private static final Set<String> STOP = Set.of("readme", "docs", "update", "create", "file",
            "files", "project", "нужно", "обнови", "найди", "создай", "проект", "файл", "файлы",
            "документация", "документацию", "архитектура", "architecture", "lecture", "processor");

    private final LlmTaskResolver taskResolver;
    private final PromptTemplates prompts;
    private final WorkspaceFileService files;
    private final FileAgentProperties properties;
    private final ObjectMapper objectMapper;

    public FileAgentPlanner(LlmTaskResolver taskResolver, PromptTemplates prompts,
            WorkspaceFileService files, FileAgentProperties properties, ObjectMapper objectMapper) {
        this.taskResolver = taskResolver;
        this.prompts = prompts;
        this.files = files;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Seed plan with search queries and symbols derived from the goal. */
    public FileAgentPlan seed(String goal, FileWorkspaceRef workspace) {
        List<String> symbols = symbols(goal);
        List<String> queries = new ArrayList<>();
        queries.add(goal);
        for (String symbol : symbols) {
            queries.add(symbol);
        }
        String kind = goal.toLowerCase(Locale.ROOT).contains("adr") ? "ADR"
                : goal.toLowerCase(Locale.ROOT).contains("докум") ? "DOC_UPDATE" : "GENERAL";
        List<String> targets = suggestTargets(goal, kind, workspace);
        return new FileAgentPlan("Начальный детерминированный план по цели", queries, symbols,
                List.of(), kind, targets, List.of());
    }

    /**
     * Lets the LLM refine the plan against real search candidates. Falls back to
     * deterministic selection on any failure; the caller records the reason.
     */
    public PlanResult refine(String goal, FileAgentPlan seed, List<FileSearchHit> candidates,
            FileWorkspaceRef workspace) {
        List<String> candidatePaths = candidates.stream().map(FileSearchHit::path)
                .distinct().limit(30).toList();
        try {
            ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.FILE_AGENT);
            if (resolved == null || !resolved.provider().isAvailable()) {
                return new PlanResult(fallback(seed, candidatePaths, workspace), false,
                        "LLM недоступна — использован детерминированный план", null, null);
            }
            String user = buildPlanPrompt(goal, seed, candidates);
            LlmResult result = resolved.provider().generate(prompts.get(PromptTemplates.FILE_AGENT_PLAN),
                    user, resolved.options());
            FileAgentPlan plan = parsePlan(result.content(), seed, candidatePaths, workspace);
            return new PlanResult(plan, true, null, resolved.providerId(), resolved.model());
        } catch (RuntimeException e) {
            log.debug("File agent LLM planning failed: {}", e.getMessage());
            return new PlanResult(fallback(seed, candidatePaths, workspace), false,
                    "Планирование LLM не удалось: " + e.getMessage(), null, null);
        }
    }

    private FileAgentPlan fallback(FileAgentPlan seed, List<String> candidates,
            FileWorkspaceRef workspace) {
        List<String> selected = selectDeterministically(seed, candidates);
        List<String> targets = seed.targetPaths();
        if (targets == null || targets.isEmpty()) {
            targets = suggestTargets(seed.reasoning(), seed.proposalKind(), workspace);
        }
        return new FileAgentPlan(seed.reasoning(), seed.queries(), seed.symbols(), selected,
                seed.proposalKind(), targets, List.of("План выбран детерминированно"));
    }

    private List<String> selectDeterministically(FileAgentPlan seed, List<String> candidates) {
        List<String> ranked = new ArrayList<>(candidates);
        Set<String> preferred = new LinkedHashSet<>();
        for (String symbol : seed.symbols()) {
            for (String candidate : candidates) {
                if (candidate.toLowerCase(Locale.ROOT).contains(symbol.toLowerCase(Locale.ROOT))) {
                    preferred.add(candidate);
                }
            }
        }
        for (String candidate : candidates) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.endsWith("readme.md") || lower.startsWith("docs/")
                    || lower.contains("architecture") || lower.contains("llm")) {
                preferred.add(candidate);
            }
        }
        ranked.sort(Comparator.comparingInt(path -> preferred.contains(path) ? 0 : 1));
        List<String> result = new ArrayList<>();
        for (String path : ranked) {
            if (result.size() >= Math.min(properties.maxFilesPerTask(), 5)) {
                break;
            }
            if (!result.contains(path)) {
                result.add(path);
            }
        }
        return result;
    }

    private FileAgentPlan parsePlan(String raw, FileAgentPlan seed, List<String> candidates,
            FileWorkspaceRef workspace) {
        String json = com.lectureprocessor.fileagent.FileAgentProposalParser.extractJson(raw);
        JsonNode root = objectMapper.readTree(json);
        if (root == null || !root.isObject()) {
            throw new FileAgentException("План должен быть JSON-объектом");
        }
        List<String> readFiles = strings(root.get("readFiles")).stream()
                .filter(candidates::contains)
                .distinct()
                .limit(Math.max(1, properties.maxFilesPerTask()))
                .toList();
        if (readFiles.isEmpty()) {
            readFiles = selectDeterministically(seed, candidates);
        }
        List<String> queries = new ArrayList<>(strings(root.get("queries")));
        if (queries.isEmpty()) {
            queries = seed.queries();
        }
        List<String> symbols = new ArrayList<>(strings(root.get("symbols")));
        if (symbols.isEmpty()) {
            symbols = seed.symbols();
        }
        String kind = text(root, "proposalKind");
        if (kind == null) {
            kind = seed.proposalKind();
        }
        List<String> targets = strings(root.get("targetPaths"));
        if ("ADR".equalsIgnoreCase(kind)) {
            // ADR numbering must follow the real directory, never a model guess.
            targets = List.of(nextAdrPath(workspace));
        } else if (targets.isEmpty()) {
            targets = suggestTargets(seed.reasoning(), kind, workspace);
        }
        String reasoning = text(root, "reasoning");
        return new FileAgentPlan(reasoning == null ? seed.reasoning() : reasoning,
                queries.stream().limit(6).toList(), symbols.stream().limit(6).toList(), readFiles,
                kind, targets.stream().limit(3).toList(), strings(root.get("notes")));
    }

    /** Chooses a target path using the real docs structure, not a hardcoded name. */
    public List<String> suggestTargets(String goal, String kind, FileWorkspaceRef workspace) {
        String lower = goal == null ? "" : goal.toLowerCase(Locale.ROOT);
        if ("ADR".equalsIgnoreCase(kind) || lower.contains("adr") || lower.contains("архитектур")) {
            return List.of(nextAdrPath(workspace));
        }
        if ("LECTURE".equalsIgnoreCase(kind) || workspace.kind() == FileWorkspace.LECTURE) {
            List<String> lecture = files.list(workspace, "", 200).stream()
                    .filter(path -> path.endsWith(".md")).toList();
            if (!lecture.isEmpty()) {
                return List.of(lecture.getFirst());
            }
        }
        List<String> targets = new ArrayList<>();
        List<String> all = files.list(workspace, "", 400);
        if (all.contains("README.md")) {
            targets.add("README.md");
        }
        all.stream().filter(path -> path.startsWith("docs/") && path.endsWith(".md")
                        && (path.toLowerCase(Locale.ROOT).contains("llm")
                        || path.toLowerCase(Locale.ROOT).contains("architecture")
                        || path.toLowerCase(Locale.ROOT).contains("week7")))
                .limit(1).forEach(targets::add);
        if (targets.isEmpty()) {
            targets.add("README.md");
        }
        return targets;
    }

    private String nextAdrPath(FileWorkspaceRef workspace) {
        List<String> docs = files.list(workspace, "docs", 500);
        int max = 0;
        Pattern adr = Pattern.compile("(?i).*/adr[-_]?(\\d+).*\\.md$");
        for (String path : docs) {
            Matcher matcher = adr.matcher(path);
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        String number = String.format("%03d", max + 1);
        return "docs/adr/ADR-" + number + "-ingestion-pipeline.md";
    }

    private String buildPlanPrompt(String goal, FileAgentPlan seed, List<FileSearchHit> candidates) {
        StringBuilder candidateText = new StringBuilder();
        for (FileSearchHit hit : candidates.stream().limit(30).toList()) {
            candidateText.append("- ").append(hit.path()).append(" (score=").append(hit.score())
                    .append(", size=").append(hit.sizeBytes()).append(")\n");
        }
        return """
                <untrusted_goal>
                %s
                </untrusted_goal>

                Начальные поиски: %s
                Начальные символы: %s
                Предлагаемый тип: %s

                candidate files:
                %s

                Верни JSON плана строго по схеме из системного промпта.
                """.formatted(goal, String.join(", ", seed.queries()),
                String.join(", ", seed.symbols()), seed.proposalKind(), candidateText);
    }

    private static List<String> symbols(String goal) {
        Set<String> result = new LinkedHashSet<>();
        if (goal != null) {
            Matcher backtick = BACKTICK.matcher(goal);
            while (backtick.find()) {
                result.add(backtick.group(1).strip());
            }
            Matcher identifier = IDENTIFIER.matcher(goal);
            while (identifier.find()) {
                String value = identifier.group();
                if (!STOP.contains(value.toLowerCase(Locale.ROOT)) && !value.equals("ADR")) {
                    result.add(value);
                }
            }
        }
        return result.stream().limit(6).toList();
    }

    private static List<String> strings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item != null && item.isString() && !item.asText().isBlank()) {
                    result.add(item.asText().strip());
                }
            });
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() && !value.asText().isBlank()
                ? value.asText().strip() : null;
    }

    /** Result of planning plus an explicit fallback reason for the UI. */
    public record PlanResult(FileAgentPlan plan, boolean llmUsed, String fallbackReason,
            String provider, String model) {
    }

    /** Kept for tests that assert the timeout budget is non-zero. */
    public Duration timeout() {
        return properties.timeout();
    }
}
