package com.lectureprocessor.chat;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.lectureprocessor.config.AppProperties;
import com.lectureprocessor.domain.ChatMessage;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.RagAnswer;
import com.lectureprocessor.llm.LlmTaskResolver;
import com.lectureprocessor.llm.LlmTaskType;
import com.lectureprocessor.llm.ResolvedLlmTask;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.rag.RagService;
import com.lectureprocessor.repository.ChatMessageRepository;

/**
 * Per-lecture chat: persists history and produces grounded answers via RAG.
 */
@Service
public class LectureChatService {

    private static final Logger log = LoggerFactory.getLogger(LectureChatService.class);

    private final ChatMessageRepository chatRepository;
    private final RagService ragService;
    private final LectureService lectureService;
    private final LlmTaskResolver taskResolver;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public LectureChatService(ChatMessageRepository chatRepository, RagService ragService,
            LectureService lectureService, LlmTaskResolver taskResolver, AppProperties properties,
            ObjectMapper objectMapper) {
        this.chatRepository = chatRepository;
        this.ragService = ragService;
        this.lectureService = lectureService;
        this.taskResolver = taskResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public List<ChatMessage> history(String lectureId, String sessionId) {
        return chatRepository.findBySession(lectureId, sessionId);
    }

    public RagAnswer ask(String lectureId, String sessionId, String question) {
        Lecture lecture = lectureService.getRequired(lectureId);
        String trimmed = question == null ? "" : question.strip();
        int maxChars = properties.chat().maxQuestionChars();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Вопрос не может быть пустым");
        }
        if (trimmed.length() > maxChars) {
            throw new IllegalArgumentException("Вопрос слишком длинный (максимум " + maxChars + " символов)");
        }

        chatRepository.save(lectureId, sessionId, ChatMessage.ROLE_USER, trimmed, null);

        List<String> recent = new ArrayList<>();
        List<ChatMessage> history = chatRepository.findBySession(lectureId, sessionId);
        int from = Math.max(0, history.size() - properties.chat().maxHistoryMessages());
        for (ChatMessage message : history.subList(from, history.size() - 1)) {
            recent.add(message.role() + ": " + message.content());
        }

        ResolvedLlmTask resolved = taskResolver.resolve(LlmTaskType.LECTURE_PROCESSING);
        RagAnswer answer = ragService.answer(lectureId, lecture.originalFilename(), trimmed, recent,
                resolved.options());

        chatRepository.save(lectureId, sessionId, ChatMessage.ROLE_ASSISTANT, answer.text(),
                serializeSources(answer));
        log.debug("Chat answer for lecture {} ({} sources)", lectureId, answer.sources().size());
        return answer;
    }

    public void clear(String lectureId, String sessionId) {
        chatRepository.deleteBySession(lectureId, sessionId);
    }

    private String serializeSources(RagAnswer answer) {
        if (answer.sources().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(answer.sources());
        } catch (JacksonException e) {
            log.warn("Cannot serialize sources: {}", e.getMessage());
            return null;
        }
    }
}
