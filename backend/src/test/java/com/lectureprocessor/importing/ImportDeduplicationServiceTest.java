package com.lectureprocessor.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lectureprocessor.domain.JobStatus;
import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.repository.LectureRepository;
import com.lectureprocessor.repository.MediaSourceRepository;

/**
 * The wizard relies on this service to avoid creating two lectures from one
 * click. These tests cover the real decision points, not just the happy path.
 */
class ImportDeduplicationServiceTest {

    private MediaSourceRepository mediaSources;
    private LectureRepository lectures;
    private ImportDeduplicationService service;

    @BeforeEach
    void setUp() {
        mediaSources = mock(MediaSourceRepository.class);
        lectures = mock(LectureRepository.class);
        service = new ImportDeduplicationService(mediaSources, lectures);
    }

    @AfterEach
    void tearDown() {
        service = null;
    }

    @Test
    void repeatedRequestIdResolvesToTheSameLecture() {
        when(lectures.findById("l1")).thenReturn(Optional.of(lecture("l1")));
        service.remember("req-1", "l1");

        assertThat(service.previousRequest("req-1")).contains("l1");
        assertThat(service.previousRequest("  req-1 ")).contains("l1");
        assertThat(service.previousRequest("other")).isEmpty();
    }

    @Test
    void blankRequestIdIsIgnored() {
        service.remember(null, "l1");
        service.remember("", "l1");
        service.remember("   ", "l1");

        assertThat(service.previousRequest("")).isEmpty();
        assertThat(service.previousRequest(null)).isEmpty();
    }

    @Test
    void deletedLectureIsNotResurrected() {
        when(lectures.findById("l1")).thenReturn(Optional.empty());
        service.remember("req-1", "l1");

        assertThat(service.previousRequest("req-1")).isEmpty();
        // The stale entry is dropped, a later re-submit is a fresh attempt.
        when(lectures.findById("l1")).thenReturn(Optional.of(lecture("l1")));
        assertThat(service.previousRequest("req-1")).isEmpty();
    }

    @Test
    void canonicalUrlMatchesAnExistingLecture() {
        MediaSource source = MediaSource.remote("l1", "https://WWW.YouTube.com/watch?v=abc#t=10",
                "720p", false);
        when(mediaSources.findAll()).thenReturn(List.of(source));
        when(lectures.findById("l1")).thenReturn(Optional.of(lecture("l1")));

        assertThat(service.existingLectureForUrl("https://www.youtube.com/watch?v=abc"))
                .contains("l1");
        assertThat(service.existingLectureForUrl("https://www.youtube.com/watch?v=other"))
                .isEmpty();
        assertThat(service.existingLectureForUrl(null)).isEmpty();
        assertThat(service.existingLectureForUrl("   ")).isEmpty();
    }

    @Test
    void urlMatchIgnoresMissingLectures() {
        when(mediaSources.findAll()).thenReturn(List.of(
                MediaSource.remote("gone", "https://rutube.ru/video/1/", "720p", false)));
        when(lectures.findById("gone")).thenReturn(Optional.empty());

        assertThat(service.existingLectureForUrl("https://rutube.ru/video/1")).isEmpty();
    }

    @Test
    void repositoryFailureDoesNotBreakTheImport() {
        when(mediaSources.findAll()).thenThrow(new IllegalStateException("db down"));

        assertThat(service.existingLectureForUrl("https://youtu.be/abc")).isEmpty();
    }

    private static Lecture lecture(String id) {
        Instant now = Instant.now();
        return new Lecture(id, "lecture.mp4", "source.mp4", 10, "video/mp4",
                JobStatus.UPLOADED, null, null, false, false, null, null, null, null, now, now);
    }
}
