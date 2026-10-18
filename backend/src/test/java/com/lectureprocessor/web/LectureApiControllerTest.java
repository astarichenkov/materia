package com.lectureprocessor.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lectureprocessor.chat.LectureChatService;
import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.job.LectureJobService;
import com.lectureprocessor.lecture.LectureNotFoundException;
import com.lectureprocessor.lecture.LectureService;
import com.lectureprocessor.lecture.RemoteLectureService;
import com.lectureprocessor.pipeline.JobStatusService;
import com.lectureprocessor.processing.OutputVariantService;
import com.lectureprocessor.repository.SlideRepository;
import com.lectureprocessor.storage.FileStorageService;

@WebMvcTest(controllers = LectureApiController.class)
class LectureApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LectureService lectureService;
    @MockitoBean
    private RemoteLectureService remoteLectureService;
    @MockitoBean
    private LectureJobService jobService;
    @MockitoBean
    private JobStatusService jobStatusService;
    @MockitoBean
    private LectureChatService chatService;
    @MockitoBean
    private SlideRepository slideRepository;
    @MockitoBean
    private FileStorageService storage;
    @MockitoBean
    private OutputVariantService outputVariants;

    private static Lecture lecture(String id) {
        Instant now = Instant.now();
        return new Lecture(id, "lecture.mp4", "source.mp4", 100, "video/mp4",
                JobStatus.UPLOADED, null, null, false, false, null, null, null, null, now, now);
    }

    @Test
    void listsLectures() throws Exception {
        when(lectureService.findAll()).thenReturn(List.of());
        mockMvc.perform(get("/api/lectures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void uploadsLecture() throws Exception {
        when(lectureService.create(any(), anyBoolean(), anyBoolean(), anyString(), anyString(),
                anyBoolean(), anyString())).thenReturn(lecture("abc"));
        MockMultipartFile file = new MockMultipartFile("file", "lecture.mp4", "video/mp4", new byte[10]);

        mockMvc.perform(multipart("/api/lectures").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("abc"))
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    void submitsProcessing() throws Exception {
        when(lectureService.getRequired("abc")).thenReturn(lecture("abc"));
        when(jobService.submit("abc")).thenReturn(true);

        mockMvc.perform(post("/api/lectures/abc/process"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.submitted").value(true));
    }

    @Test
    void unknownLectureReturns404() throws Exception {
        when(lectureService.getRequired("missing")).thenThrow(new LectureNotFoundException("missing"));
        mockMvc.perform(get("/api/lectures/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void oversizedChatQuestionReturns400() throws Exception {
        when(chatService.ask(eq("abc"), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Вопрос слишком длинный"));

        mockMvc.perform(post("/api/lectures/abc/chat")
                        .param("session", "s1")
                        .param("question", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Вопрос слишком длинный"));
    }

    @Test
    void deletesLecture() throws Exception {
        mockMvc.perform(delete("/api/lectures/abc"))
                .andExpect(status().isNoContent());
    }
}
