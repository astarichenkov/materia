package com.lectureprocessor.codereview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Structured JSON extraction from model output. */
class ReviewResultParserTest {

    private final ReviewResultParser parser = new ReviewResultParser();

    @Test
    void parsesValidJson() {
        String json = """
                {"summary":"Two issues","overallRisk":"HIGH","findings":[
                  {"category":"BUG","severity":"HIGH","file":"src/A.java","line":42,
                   "title":"Null deref","description":"possible NPE","suggestion":"guard","confidence":0.9}],
                 "recommendations":["add a test"]}
                """;
        RawReview review = parser.parse(json);
        assertThat(review.summary()).isEqualTo("Two issues");
        assertThat(review.overallRisk()).isEqualTo("HIGH");
        assertThat(review.findings()).hasSize(1);
        assertThat(review.findings().getFirst().line()).isEqualTo(42);
        assertThat(review.recommendations()).containsExactly("add a test");
    }

    @Test
    void toleratesMarkdownFencesAndProse() {
        String raw = "Here is the review:\n```json\n{\"summary\":\"ok\",\"findings\":[]}\n```\nDone.";
        RawReview review = parser.parse(raw);
        assertThat(review.summary()).isEqualTo("ok");
        assertThat(review.findings()).isEmpty();
    }

    @Test
    void acceptsFieldAliases() {
        String json = """
                {"review_summary":"s","risk":"MEDIUM","findings":[
                  {"type":"SECURITY","priority":"critical","path":"p.java","line_number":"7",
                   "name":"Secret","details":"leaked","fix":"remove","score":0.8}]}
                """;
        RawReview review = parser.parse(json);
        assertThat(review.summary()).isEqualTo("s");
        assertThat(review.overallRisk()).isEqualTo("MEDIUM");
        assertThat(review.findings().getFirst().category()).isEqualTo("SECURITY");
        assertThat(review.findings().getFirst().line()).isEqualTo(7);
    }

    @Test
    void rejectsEmptyAndNonJson() {
        assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(ReviewParseException.class);
        assertThatThrownBy(() -> parser.parse("no json here"))
                .isInstanceOf(ReviewParseException.class);
    }

    @Test
    void parsesSingleRecommendationString() {
        RawReview review = parser.parse("{\"summary\":\"s\",\"recommendation\":\"do it\"}");
        assertThat(review.recommendations()).containsExactly("do it");
        assertThat(review.findings()).isEmpty();
    }

    @Test
    void ignoresNonObjectFindings() {
        RawReview review = parser.parse("{\"findings\":[1,\"x\",null,{\"title\":\"t\"}]}");
        assertThat(review.findings()).hasSize(1);
        assertThat(review.findings().getFirst().title()).isEqualTo("t");
        assertThat(List.of(review.findings()).getFirst()).isNotNull();
    }
}
