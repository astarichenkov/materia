package com.lectureprocessor.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupportIntentRouterTest {

    private final SupportIntentRouter router = new SupportIntentRouter();

    @Test
    void faqQuestionRoutesToDocs() {
        SupportRoute route = router.route("Как подключить DeepSeek и выбрать его для обработки?",
                false, false);
        assertThat(route.intent()).isEqualTo(SupportIntent.GENERAL_FAQ);
        assertThat(route.tools()).isEmpty();
    }

    @Test
    void diagnosticQuestionWithLectureRoutesToDiagnostics() {
        SupportRoute route = router.route("Почему обработка остановилась?", false, true);
        assertThat(route.intent()).isEqualTo(SupportIntent.JOB_DIAGNOSTICS);
        assertThat(route.tools()).contains("get_job_status", "get_stage_runs", "get_job_diagnostics");
    }

    @Test
    void ticketWithLectureRoutesToMixed() {
        SupportRoute route = router.route("Что произошло при обработке?", true, true);
        assertThat(route.intent()).isEqualTo(SupportIntent.MIXED);
        assertThat(route.tools()).contains("get_ticket", "list_ticket_messages", "get_job_diagnostics");
    }

    @Test
    void ticketOnlyRoutesToTicketContext() {
        SupportRoute route = router.route("Продолжим по обращению", true, false);
        assertThat(route.intent()).isEqualTo(SupportIntent.TICKET_CONTEXT);
        assertThat(route.tools()).containsExactly("get_ticket", "list_ticket_messages");
    }

    @Test
    void modelQuestionAddsHealthTool() {
        SupportRoute route = router.route("Доступна ли Ollama?", false, true);
        assertThat(route.tools()).contains("get_model_health");
    }
}
