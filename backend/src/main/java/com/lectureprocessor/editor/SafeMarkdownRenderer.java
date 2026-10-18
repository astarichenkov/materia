package com.lectureprocessor.editor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Minimal, strict Markdown renderer used for the notes preview.
 *
 * <p>Raw HTML is never passed through: the whole input is HTML-escaped first and
 * the renderer only emits its own whitelisted tags. Links and images are limited
 * to {@code http} / {@code https} URLs. This makes the preview safe against
 * stored XSS without pulling an extra dependency into the build.</p>
 */
@Component
public class SafeMarkdownRenderer {

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern ORDERED = Pattern.compile("^\\d+[.)]\\s+.*");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    public String render(String markdown, int maxChars) {
        if (markdown == null || markdown.isBlank()) {
            return "<p class=\"muted\">Документ пуст.</p>";
        }
        String input = markdown.length() > maxChars ? markdown.substring(0, maxChars) : markdown;
        StringBuilder html = new StringBuilder();
        String[] lines = input.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inCode = false;
        StringBuilder paragraph = new StringBuilder();
        List<String> list = new ArrayList<>();
        boolean ordered = false;
        List<String> table = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.strip();
            if (line.startsWith("```")) {
                flushParagraph(html, paragraph);
                flushList(html, list, ordered);
                flushTable(html, table);
                if (inCode) {
                    html.append("</code></pre>\n");
                } else {
                    html.append("<pre><code>");
                }
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                html.append(escape(raw)).append('\n');
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(html, paragraph);
                flushList(html, list, ordered);
                flushTable(html, table);
                int level = heading.group(1).length();
                html.append("<h").append(level).append('>')
                        .append(inline(heading.group(2)))
                        .append("</h").append(level).append(">\n");
                continue;
            }
            if (line.isEmpty()) {
                flushParagraph(html, paragraph);
                flushList(html, list, ordered);
                flushTable(html, table);
                continue;
            }
            if (line.startsWith("|")) {
                flushParagraph(html, paragraph);
                flushList(html, list, ordered);
                table.add(line);
                continue;
            }
            flushTable(html, table);
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")) {
                flushParagraph(html, paragraph);
                if (!list.isEmpty() && ordered) {
                    flushList(html, list, true);
                }
                ordered = false;
                list.add(line.substring(2));
                continue;
            }
            if (ORDERED.matcher(line).matches()) {
                flushParagraph(html, paragraph);
                if (!list.isEmpty() && !ordered) {
                    flushList(html, list, false);
                }
                ordered = true;
                list.add(line.replaceFirst("^\\d+[.)]\\s+", ""));
                continue;
            }
            if (line.startsWith("> ")) {
                flushParagraph(html, paragraph);
                flushList(html, list, ordered);
                html.append("<blockquote>").append(inline(line.substring(2)))
                        .append("</blockquote>\n");
                continue;
            }
            flushList(html, list, ordered);
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(line);
        }
        if (inCode) {
            html.append("</code></pre>\n");
        }
        flushParagraph(html, paragraph);
        flushList(html, list, ordered);
        flushTable(html, table);
        return html.toString();
    }

    private static void flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() == 0) {
            return;
        }
        html.append("<p>").append(inline(paragraph.toString())).append("</p>\n");
        paragraph.setLength(0);
    }

    private static void flushList(StringBuilder html, List<String> list, boolean ordered) {
        if (list.isEmpty()) {
            return;
        }
        String tag = ordered ? "ol" : "ul";
        html.append('<').append(tag).append(">\n");
        for (String item : list) {
            html.append("<li>").append(inline(item)).append("</li>\n");
        }
        html.append("</").append(tag).append(">\n");
        list.clear();
    }

    private static void flushTable(StringBuilder html, List<String> rows) {
        if (rows.isEmpty()) {
            return;
        }
        html.append("<table>\n");
        boolean header = true;
        for (String row : rows) {
            if (row.matches("^\\|[\\s:|-]+\\|$")) {
                header = false;
                continue;
            }
            String body = row.substring(1, row.endsWith("|") ? row.length() - 1 : row.length());
            String[] cells = body.split("\\|", -1);
            html.append("<tr>");
            for (String cell : cells) {
                String tag = header ? "th" : "td";
                html.append('<').append(tag).append('>').append(inline(cell.strip()))
                        .append("</").append(tag).append('>');
            }
            html.append("</tr>\n");
            header = false;
        }
        html.append("</table>\n");
        rows.clear();
    }

    private static String inline(String text) {
        String value = escape(text);
        value = IMAGE.matcher(value).replaceAll(match -> {
            String alt = match.group(1);
            String url = safeUrl(match.group(2));
            return url == null ? alt
                    : "<img src=\"" + url + "\" alt=\"" + escapeAttribute(alt) + "\">";
        });
        value = LINK.matcher(value).replaceAll(match -> {
            String url = safeUrl(match.group(2));
            return url == null ? match.group(1)
                    : "<a href=\"" + url + "\" rel=\"noopener noreferrer nofollow\" target=\"_blank\">"
                            + match.group(1) + "</a>";
        });
        value = CODE.matcher(value).replaceAll(match -> "<code>" + match.group(1) + "</code>");
        value = BOLD.matcher(value).replaceAll(match -> "<strong>" + match.group(1) + "</strong>");
        value = ITALIC.matcher(value).replaceAll(match -> "<em>" + match.group(1) + "</em>");
        return value;
    }

    /** Only absolute http(s) URLs without control characters are accepted. */
    static String safeUrl(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(candidate);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (uri.getHost() == null || candidate.chars().anyMatch(Character::isISOControl)) {
                return null;
            }
            return escapeAttribute(uri.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeAttribute(String value) {
        return escape(value).replace("`", "&#96;");
    }
}
