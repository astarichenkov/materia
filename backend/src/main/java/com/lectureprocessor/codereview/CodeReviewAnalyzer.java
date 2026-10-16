package com.lectureprocessor.codereview;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.llm.CloudCostService;
import com.lectureprocessor.llm.LlmException;
import com.lectureprocessor.llm.LlmResult;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;

/**
 * Runs the LLM review over the assembled context and returns validated,
 * structured findings. Provider/model come exclusively from
 * {@link LlmTaskType#CODE_REVIEW}; nothing is hardcoded here.
 *
 * <p>Malformed JSON triggers exactly one bounded repair attempt, then the review
 * fails cleanly instead of publishing raw output.</p>
 */
@Service
public class CodeReviewAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(CodeReviewAnalyzer.class);

    private final LlmTaskResolver taskResolver;
    private final ReviewPromptBuilder promptBuilder;
    private final ReviewResultParser parser;
    private final ReviewValidator validator;
    private final CloudCostService costs;
    private final AppProperties properties;

    public CodeReviewAnalyzer(LlmTaskResolver taskResolver, ReviewPromptBuilder promptBuilder,
            ReviewResultParser parser, ReviewValidator validator, CloudCostService costs,
            AppProperties properties) {
        this.taskResolver = taskResolver;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.validator = validator;
        this.costs = costs;
        this.properties = properties;
    }

    public ReviewResult analyze(ReviewContext context, ReviewDiffInfo diffInfo) {
        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.CODE_REVIEW);
        AppProperties.CodeReview config = properties.codeReview();
        int maxFindings = config.maxFindings();
        String system = promptBuilder.systemPrompt(maxFindings);
        String user = promptBuilder.userPrompt(context);

        long start = System.nanoTime();
        int inputTokens = 0;
        int outputTokens = 0;
        int calls = 0;
        String provider = resolved.providerId();
        String model = resolved.model();
        boolean fallback = resolved.fallback();
        boolean cloud = resolved.cloud();

        try {
            LlmResult result = resolved.provider().generate(system, user, resolved.options());
            calls++;
            inputTokens += result.inputTokens();
            outputTokens += result.outputTokens();
            RawReview raw;
            try {
                raw = parser.parse(result.content());
            } catch (ReviewParseException firstFailure) {
                log.warn("Review JSON invalid ({}); attempting one repair call", firstFailure.getMessage());
                if (calls >= Math.max(1, config.maxLlmCalls())) {
                    throw firstFailure;
                }
                String repair = user + "\n\nIMPORTANT: your previous answer was not valid JSON. "
                        + "Return ONLY the JSON object described in the system instructions.";
                LlmResult repaired = resolved.provider().generate(system, repair, resolved.options());
                calls++;
                inputTokens += repaired.inputTokens();
                outputTokens += repaired.outputTokens();
                raw = parser.parse(repaired.content());
            }
            Set<String> changedFiles = context.changedFiles().stream()
                    .map(com.lectureprocessor.gitea.GiteaChangedFile::filename)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            Map<String, Set<Integer>> anchors = CodeReviewContextBuilder.anchors(diffInfo.files());
            ParsedReview parsed = validator.validate(raw, changedFiles, anchors, maxFindings);
            long llmMs = (System.nanoTime() - start) / 1_000_000;
            Double estimatedCost = null;
            String currency = costs.currency();
            if (cloud) {
                CloudCostService.CostEstimate estimate = costs.estimate(inputTokens, outputTokens, 0, 0);
                estimatedCost = estimate.priced() ? estimate.amount() : null;
                currency = estimate.currency();
            }
            log.info("AI review analysed {} (provider={} model={} findings={} tokens={}/{})",
                    context.repository() + "#" + context.prNumber(), provider, model,
                    parsed.findings().size(), inputTokens, outputTokens);
            return new ReviewResult(parsed.summary(), parsed.findings(), parsed.recommendations(),
                    parsed.overallRisk(), provider, model, fallback, cloud, context.partial(),
                    context.analyzedFiles(), context.skippedFiles(), inputTokens, outputTokens, calls, llmMs,
                    null);
        } catch (LlmException e) {
            long llmMs = (System.nanoTime() - start) / 1_000_000;
            return new ReviewResult(null, List.of(), List.of(), "UNKNOWN", provider, model, fallback, cloud,
                    context.partial(), context.analyzedFiles(), context.skippedFiles(), inputTokens,
                    outputTokens, calls, llmMs, e.getMessage());
        } catch (ReviewParseException e) {
            long llmMs = (System.nanoTime() - start) / 1_000_000;
            return new ReviewResult(null, List.of(), List.of(), "UNKNOWN", provider, model, fallback, cloud,
                    context.partial(), context.analyzedFiles(), context.skippedFiles(), inputTokens,
                    outputTokens, calls, llmMs, "Невалидный JSON от модели: " + e.getMessage());
        }
    }
}
