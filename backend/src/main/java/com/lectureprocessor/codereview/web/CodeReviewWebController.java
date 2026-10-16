package com.lectureprocessor.codereview.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.lectureprocessor.codereview.CodeReviewJob;
import com.lectureprocessor.codereview.CodeReviewService;
import com.lectureprocessor.codereview.ReviewFinding;
import com.lectureprocessor.codereview.ReviewResult;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.gitea.GiteaClient;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.llm.LlmTaskSettingsService;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.web.security.AdminAccessService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Thymeleaf dashboard for the automated AI code review. Model selection is not
 * duplicated here: the page links to {@code /settings → Code Review}.
 */
@Controller
@RequestMapping("/code-review")
public class CodeReviewWebController {

    private final CodeReviewService reviews;
    private final LlmTaskSettingsService taskSettings;
    private final GiteaClient gitea;
    private final AppProperties properties;
    private final AdminAccessService access;

    public CodeReviewWebController(CodeReviewService reviews, LlmTaskSettingsService taskSettings,
            GiteaClient gitea, AppProperties properties, AdminAccessService access) {
        this.reviews = reviews;
        this.taskSettings = taskSettings;
        this.gitea = gitea;
        this.properties = properties;
        this.access = access;
    }

    @GetMapping
    public String dashboard(Model model, HttpServletRequest request) {
        addCommon(model, request);
        model.addAttribute("jobs", reviews.history(25));
        model.addAttribute("totals", totals());
        return "code-review";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model, HttpServletRequest request) {
        addCommon(model, request);
        CodeReviewJob job = reviews.get(id);
        List<ReviewFinding> findings = reviews.findings(id);
        model.addAttribute("job", job);
        model.addAttribute("findings", findings);
        return "code-review-detail";
    }

    @PostMapping("/manual")
    public String manual(@RequestParam("repository") String repository,
            @RequestParam("prNumber") long prNumber,
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
            RedirectAttributes redirect) {
        try {
            if (dryRun) {
                ReviewResult result = reviews.preview(repository, prNumber, null);
                redirect.addFlashAttribute("message",
                        "Dry-run: provider=" + result.provider() + "/" + result.model()
                                + ", findings=" + result.findings().size()
                                + ", tokens=" + result.inputTokens() + "/" + result.outputTokens());
            } else {
                CodeReviewJob job = reviews.enqueueManual(repository, prNumber, force);
                redirect.addFlashAttribute("message", "Review #" + job.id() + " поставлен в очередь");
            }
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/code-review";
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable long id, RedirectAttributes redirect) {
        try {
            CodeReviewJob job = reviews.retry(id);
            redirect.addFlashAttribute("message", "Повторный review #" + job.id() + " поставлен в очередь");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/code-review";
    }

    private void addCommon(Model model, HttpServletRequest request) {
        LlmRunConfig config = taskSettings.configFor(LlmTaskType.CODE_REVIEW);
        model.addAttribute("provider", config.provider());
        model.addAttribute("modelName", config.model());
        model.addAttribute("giteaConfigured", gitea.configured());
        model.addAttribute("giteaBaseUrl", gitea.baseUrl());
        model.addAttribute("webhookConfigured", properties.gitea().webhookConfigured());
        model.addAttribute("webhookUrl", "/api/code-review/webhook/gitea");
        model.addAttribute("pollingEnabled", properties.codeReview().pollingEnabled());
        model.addAttribute("publish", properties.codeReview().publish());
        model.addAttribute("allowedRepositories", properties.gitea().allowedRepositories());
        model.addAttribute("csrfToken", access.csrfToken(request.getSession(true)));
    }

    private Totals totals() {
        List<CodeReviewJob> all = reviews.history(200);
        int completed = 0;
        int failed = 0;
        int findings = 0;
        long tokens = 0;
        double cost = 0;
        boolean priced = false;
        for (CodeReviewJob job : all) {
            if ("COMPLETED".equals(job.status())) {
                completed++;
            } else if ("FAILED".equals(job.status())) {
                failed++;
            }
            findings += job.findingsCount();
            if (job.inputTokens() != null) {
                tokens += job.inputTokens();
            }
            if (job.outputTokens() != null) {
                tokens += job.outputTokens();
            }
            if (job.estimatedCost() != null) {
                cost += job.estimatedCost();
                priced = true;
            }
        }
        return new Totals(all.size(), completed, failed, findings, tokens, cost, priced);
    }

    /** Aggregate dashboard metrics. */
    public record Totals(int total, int completed, int failed, int findings, long tokens, double estimatedCost,
            boolean priced) {
    }
}
