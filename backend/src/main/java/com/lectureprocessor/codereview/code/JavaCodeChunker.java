package com.lectureprocessor.codereview.code;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, dependency-free structural chunker for Java sources. It prefers
 * class and method boundaries (rather than arbitrary character windows) and
 * records 1-based line ranges so findings can reference real lines.
 *
 * <p>It is intentionally heuristic: it strips comments/strings before brace
 * counting and falls back to fixed-size windows for very large blocks. It never
 * parses semantics, so it cannot fail on unusual but valid source.</p>
 */
public final class JavaCodeChunker {

    private static final Pattern TYPE = Pattern.compile(
            "\\b(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern METHOD = Pattern.compile(
            "^[\\s\\w<>\\[\\],@$.?]+\\b(\\w+)\\s*\\([^;]*$");

    private JavaCodeChunker() {
    }

    public static List<CodeSection> chunk(String content, int maxChars) {
        List<CodeSection> sections = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return sections;
        }
        List<String> lines = List.of(content.split("\n", -1));
        int total = lines.size();

        int firstType = firstTypeLine(lines);
        if (firstType > 1) {
            addSections(sections, "header", "FILE", 1, firstType - 1, lines, maxChars);
        }

        int line = Math.max(1, firstType);
        while (line <= total) {
            Block block = typeBlock(lines, line);
            if (block == null) {
                break;
            }
            addSections(sections, block.name() + " (signature)", "CLASS", block.startLine(),
                    Math.min(block.startLine() + 4, block.endLine()), lines, maxChars);
            addAll(sections, methodSections(lines, block, maxChars));
            line = block.endLine() + 1;
        }
        return sections;
    }

    private static int firstTypeLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (TYPE.matcher(strip(lines.get(i))).find()) {
                return i + 1;
            }
        }
        return lines.size() + 1;
    }

    private static Block typeBlock(List<String> lines, int startLine) {
        int depth = 0;
        boolean opened = false;
        String name = null;
        for (int i = startLine - 1; i < lines.size(); i++) {
            String code = strip(lines.get(i));
            Matcher matcher = TYPE.matcher(code);
            if (name == null && matcher.find()) {
                name = matcher.group(2);
            }
            int before = depth;
            depth += count(code, '{') - count(code, '}');
            if (!opened && depth > before) {
                opened = true;
            }
            if (opened && depth == 0) {
                return new Block(name == null ? "type" : name, startLine, i + 1);
            }
        }
        return name == null ? null : new Block(name, startLine, lines.size());
    }

    private static List<CodeSection> methodSections(List<String> lines, Block type, int maxChars) {
        List<CodeSection> sections = new ArrayList<>();
        int depth = 0;
        int start = -1;
        String name = null;
        for (int i = type.startLine() - 1; i < type.endLine() && i < lines.size(); i++) {
            String code = strip(lines.get(i));
            boolean methodStart = depth == 1 && METHOD.matcher(code).matches();
            if (methodStart) {
                if (start > 0) {
                    addSections(sections, name == null ? "method" : name, "METHOD", start, i, lines, maxChars);
                }
                Matcher matcher = METHOD.matcher(code);
                name = matcher.matches() ? matcher.group(1) : "method";
                start = i + 1;
            }
            int before = depth;
            depth += count(code, '{') - count(code, '}');
            if (start > 0 && before >= 1 && depth < 1) {
                addSections(sections, name == null ? "method" : name, "METHOD", start, i + 1, lines, maxChars);
                start = -1;
            }
        }
        if (start > 0) {
            addSections(sections, name == null ? "method" : name, "METHOD", start, type.endLine(), lines, maxChars);
        }
        return sections;
    }

    private static void addAll(List<CodeSection> target, List<CodeSection> source) {
        target.addAll(source);
    }

    private static void addSections(List<CodeSection> target, String symbol, String type, int startLine,
            int endLine, List<String> lines, int maxChars) {
        int from = Math.max(1, startLine);
        int to = Math.min(lines.size(), endLine);
        if (to < from) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (int i = from; i <= to; i++) {
            text.append(lines.get(i - 1)).append('\n');
        }
        if (text.length() <= maxChars) {
            target.add(new CodeSection(symbol, type, from, to, text.toString().stripTrailing()));
            return;
        }
        int windowLines = Math.max(20, maxChars / 60);
        for (int i = from; i <= to; i += windowLines) {
            int end = Math.min(to, i + windowLines - 1);
            StringBuilder window = new StringBuilder();
            for (int j = i; j <= end; j++) {
                window.append(lines.get(j - 1)).append('\n');
            }
            target.add(new CodeSection(symbol + "#" + i, type, i, end, window.toString().stripTrailing()));
        }
    }

    /** Strips line comments, block comments and string/char literals before brace counting. */
    static String strip(String line) {
        StringBuilder out = new StringBuilder();
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int count(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private record Block(String name, int startLine, int endLine) {
    }
}
