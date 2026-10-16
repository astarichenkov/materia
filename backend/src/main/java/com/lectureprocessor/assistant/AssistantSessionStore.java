package com.lectureprocessor.assistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lectureprocessor.config.AppProperties;

/**
 * Bounded in-memory history for assistant sessions. Keeps only the most recent
 * turns and evicts the least recently used sessions, so a long-running process
 * cannot grow without bound.
 */
@Component
public class AssistantSessionStore {

    private static final int MAX_SESSIONS = 200;

    private final int maxTurns;
    private final Map<String, Deque<String>> sessions = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Deque<String>> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    public AssistantSessionStore(AppProperties properties) {
        this.maxTurns = Math.max(0, properties.assistant().historyMessages());
    }

    public List<String> history(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        synchronized (sessions) {
            Deque<String> turns = sessions.get(sessionId);
            return turns == null ? List.of() : new ArrayList<>(turns);
        }
    }

    public void append(String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank() || maxTurns == 0) {
            return;
        }
        synchronized (sessions) {
            Deque<String> turns = sessions.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
            turns.addLast(role + ": " + content);
            while (turns.size() > maxTurns * 2L) {
                turns.removeFirst();
            }
        }
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }
}
