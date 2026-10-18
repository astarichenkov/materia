package com.lectureprocessor.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.storage.FileStorageService;
import com.lectureprocessor.support.TestProperties;

class LectureServiceTest {

    private final LectureRepository repository = mock(LectureRepository.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final com.lectureprocessor.multilingual.LectureLanguageRepository languages =
            mock(com.lectureprocessor.multilingual.LectureLanguageRepository.class);
    private final LectureService service = new LectureService(repository, storage,
            TestProperties.defaults("./target/test-data"), languages,
            com.lectureprocessor.multilingual.MultilingualProperties.defaults());

    @Test
    void createsLectureForValidUpload() {
        when(storage.saveSource(anyString(), anyString(), any())).thenReturn(Path.of("source.mp4"));
        MockMultipartFile file = new MockMultipartFile("file", "lecture.mp4", "video/mp4", new byte[100]);

        Lecture lecture = service.create(file, true, false);

        assertThat(lecture.originalFilename()).isEqualTo("lecture.mp4");
        assertThat(lecture.status()).isEqualTo(JobStatus.UPLOADED);
        assertThat(lecture.cleanTranscript()).isTrue();
        verify(repository).save(any(Lecture.class));
    }

    @Test
    void acceptsAudioUploadAndNeverEnablesSlides() {
        when(storage.saveSource(anyString(), anyString(), any())).thenReturn(Path.of("source.mp3"));
        MockMultipartFile file = new MockMultipartFile("file", "lecture.mp3", "audio/mpeg",
                new byte[100]);

        Lecture lecture = service.create(file, false, true);

        assertThat(lecture.originalFilename()).isEqualTo("lecture.mp3");
        assertThat(lecture.contentType()).isEqualTo("audio/mpeg");
        // Slides cannot exist in an audio file even if the form requested them.
        assertThat(lecture.extractSlides()).isFalse();
        assertThat(LectureService.isAudioFile("lecture.mp3")).isTrue();
        assertThat(LectureService.isAudioFile("lecture.mp4")).isFalse();
    }

    @Test
    void acceptsFlacAndWavExtensions() {
        assertThat(LectureService.isAllowedExtension("a.flac")).isTrue();
        assertThat(LectureService.isAllowedExtension("a.wav")).isTrue();
        assertThat(LectureService.isAllowedExtension("a.m4a")).isTrue();
    }

    @Test
    void rejectsEmptyUpload() {
        MockMultipartFile file = new MockMultipartFile("file", "lecture.mp4", "video/mp4", new byte[0]);
        assertThatThrownBy(() -> service.create(file, false, false))
                .isInstanceOf(InvalidUploadException.class);
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile("file", "document.pdf", "application/pdf", new byte[10]);
        assertThatThrownBy(() -> service.create(file, false, false))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("формат");
    }

    @Test
    void rejectsTooLargeFile() {
        byte[] big = new byte[2 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile("file", "lecture.mp4", "video/mp4", big);
        assertThatThrownBy(() -> service.create(file, false, false))
                .isInstanceOf(InvalidUploadException.class)
                .hasMessageContaining("большой");
    }

    @Test
    void sanitizesFilename() {
        assertThat(LectureService.sanitizeFilename("../../etc/passwd.mp4")).isEqualTo("passwd.mp4");
        assertThat(LectureService.sanitizeFilename("Моя лекция №1.mp4")).contains(".mp4");
    }
}
