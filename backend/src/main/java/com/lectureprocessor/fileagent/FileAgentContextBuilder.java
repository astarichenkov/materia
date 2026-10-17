package com.lectureprocessor.fileagent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.lectureprocessor.fileagent.model.FileAgentContext;
import com.lectureprocessor.fileagent.model.FileAgentPlan;
import com.lectureprocessor.fileagent.workspace.FileContent;

/** Bounded context aggregation for the proposal LLM call. */
@Service
public class FileAgentContextBuilder {

    private final FileAgentProperties properties;

    public FileAgentContextBuilder(FileAgentProperties properties) {
        this.properties = properties;
    }

    public FileAgentContext build(String goal, FileAgentPlan plan,
            List<String> searchResults, List<String> symbolResults, List<String> docResults,
            Map<String, FileContent> readFiles) {
        int budget = Math.max(4000, properties.maxContextChars());
        StringBuilder text = new StringBuilder();
        text.append("<untrusted_goal>\n").append(goal == null ? "" : goal).append("\n</untrusted_goal>\n\n");
        appendSection(text, "PLAN", plan == null ? "" : plan.toText(), budget / 8);
        appendSection(text, "SEARCH RESULTS", String.join("\n", safe(searchResults)), budget / 8);
        appendSection(text, "SYMBOL RESULTS", String.join("\n", safe(symbolResults)), budget / 8);
        appendSection(text, "DOCUMENTATION RAG", String.join("\n\n", safe(docResults)), budget / 6);

        int fileCount = readFiles == null ? 0 : Math.max(1, readFiles.size());
        int perFile = Math.max(1200, (budget - text.length()) / fileCount);
        Map<String, FileContent> used = new LinkedHashMap<>();
        text.append("\n<untrusted_files>\n");
        if (readFiles != null) {
            readFiles.forEach((path, content) -> {
                String body = content.text();
                boolean truncated = body.length() > perFile;
                if (truncated) {
                    body = body.substring(0, perFile) + "\n…[файл усечён по бюджету контекста]";
                }
                text.append("\n--- FILE ").append(path).append(" sha256=").append(content.sha256())
                        .append(" total_lines=").append(content.totalLines())
                        .append(truncated ? " truncated=true" : "").append(" ---\n");
                text.append(body).append('\n');
                used.put(path, content);
            });
        }
        text.append("</untrusted_files>\n");
        String bounded = text.length() <= budget ? text.toString()
                : text.substring(0, budget) + "\n…[контекст ограничен]\n";
        return new FileAgentContext(bounded, used, bounded.length());
    }

    private static void appendSection(StringBuilder text, String title, String body, int max) {
        if (body == null || body.isBlank()) {
            return;
        }
        text.append("\n<untrusted_").append(title.toLowerCase().replace(' ', '_')).append(">\n");
        text.append(body.length() <= max ? body : body.substring(0, max) + "\n…[ограничено]");
        text.append("\n</untrusted_").append(title.toLowerCase().replace(' ', '_')).append(">\n");
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
