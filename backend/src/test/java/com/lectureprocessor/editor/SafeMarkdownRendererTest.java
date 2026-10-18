package com.lectureprocessor.editor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SafeMarkdownRendererTest {

    private final SafeMarkdownRenderer renderer = new SafeMarkdownRenderer();

    @Test
    void rendersSupportedMarkdownStructures() {
        String html = renderer.render("""
                # Заголовок

                Абзац с **жирным** и *курсивом* и `кодом`.

                - пункт 1
                - пункт 2

                1. первый
                2. второй

                > цитата

                | A | B |
                |---|---|
                | 1 | 2 |

                ```java
                int x = 1 < 2;
                ```

                [ссылка](https://example.com) ![картинка](https://example.com/a.png)
                """, 20000);

        assertThat(html).contains("<h1>Заголовок</h1>");
        assertThat(html).contains("<strong>жирным</strong>");
        assertThat(html).contains("<ul>").contains("<ol>").contains("<blockquote>");
        assertThat(html).contains("<table>").contains("<pre><code>");
        assertThat(html).contains("href=\"https://example.com\"");
        assertThat(html).contains("<img src=\"https://example.com/a.png\"");
        assertThat(html).contains("int x = 1 &lt; 2;");
    }

    @Test
    void neverEmitsRawHtmlOrJavascriptUrls() {
        String html = renderer.render("""
                <script>alert('xss')</script>
                <img src=x onerror=alert(1)>
                [bad](javascript:alert(1))
                [data](data:text/html,<script>alert(1)</script>)
                **<b>bold</b>**
                """, 20000);

        assertThat(html.toLowerCase()).doesNotContain("<script");
        assertThat(html).doesNotContain("<img");
        assertThat(html).doesNotContain("<b>");
        assertThat(html).doesNotContain("href=\"javascript:");
        assertThat(html).doesNotContain("href=\"data:");
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("&lt;b&gt;bold&lt;/b&gt;");
    }

    @Test
    void capsOutputAndHandlesEmptyDocument() {
        assertThat(renderer.render("", 10)).contains("Документ пуст");
        assertThat(renderer.render(null, 10)).contains("Документ пуст");
        String html = renderer.render("a".repeat(500), 50);
        assertThat(html.length()).isLessThan(400);
    }

    @Test
    void rejectsRelativeAndControlCharacterLinks() {
        String html = renderer.render("[x](/etc/passwd) [y](file:///etc/passwd) "
                + "[z](https://example.com/\u0000)", 2000);
        assertThat(html).doesNotContain("href=\"/etc/passwd\"");
        assertThat(html).doesNotContain("href=\"file:");
    }
}
