package com.lectureprocessor.pipeline;

import java.util.ArrayList;
import java.util.List;

import com.lectureprocessor.domain.Lecture;
import com.lectureprocessor.domain.Transcript;
import com.lectureprocessor.llm.LlmRunConfig;
import com.lectureprocessor.media.MediaArtifact;
import com.lectureprocessor.media.MediaSource;
import com.lectureprocessor.processing.ProcessingProfile;
import com.lectureprocessor.process.CancellationToken;
import com.lectureprocessor.text.StructuredNotes;

/**
 * Mutable per-run context shared by pipeline stages. A context instance belongs
 * to exactly one coordinator run and is never shared across threads.
 */
public final class JobContext {

    private final String lectureId;
    private final CancellationToken cancellation;
    private final List<String> warnings = new ArrayList<>();

    private Lecture lecture;
    private MediaSource source;
    private MediaArtifact artifact;
    private MediaMetadataHolder metadata = new MediaMetadataHolder();
    private Transcript transcript;
    private StructuredNotes notes;
    private LlmRunConfig llmConfig;
    private ProcessingProfile profileOverride;
    private String runId;
    private boolean textProcessed;
    private boolean partialSuccess;

    public JobContext(String lectureId, CancellationToken cancellation) {
        this.lectureId = lectureId;
        this.cancellation = cancellation;
    }

    public String lectureId() {
        return lectureId;
    }

    public CancellationToken cancellation() {
        return cancellation;
    }

    public Lecture lecture() {
        return lecture;
    }

    public void setLecture(Lecture lecture) {
        this.lecture = lecture;
    }

    public MediaSource source() {
        return source;
    }

    public void setSource(MediaSource source) {
        this.source = source;
    }

    public MediaArtifact artifact() {
        return artifact;
    }

    public void setArtifact(MediaArtifact artifact) {
        this.artifact = artifact;
    }

    public MediaMetadataHolder metadata() {
        return metadata;
    }

    public void setMetadata(MediaMetadataHolder metadata) {
        this.metadata = metadata;
    }

    public Transcript transcript() {
        return transcript;
    }

    public void setTranscript(Transcript transcript) {
        this.transcript = transcript;
    }

    public StructuredNotes notes() {
        return notes;
    }

    public void setNotes(StructuredNotes notes) {
        this.notes = notes;
    }

    public LlmRunConfig llmConfig() {
        return llmConfig;
    }

    public void setLlmConfig(LlmRunConfig llmConfig) {
        this.llmConfig = llmConfig;
    }

    /** Requested profile used by previews; never set during a normal execution. */
    public ProcessingProfile profileOverride() {
        return profileOverride;
    }

    public void setProfileOverride(ProcessingProfile profileOverride) {
        this.profileOverride = profileOverride;
    }

    /** Identifier of the persisted pipeline run this context belongs to. */
    public String runId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public boolean textProcessed() {
        return textProcessed;
    }

    public void setTextProcessed(boolean textProcessed) {
        this.textProcessed = textProcessed;
    }

    public boolean partialSuccess() {
        return partialSuccess;
    }

    public void setPartialSuccess(boolean partialSuccess) {
        this.partialSuccess = partialSuccess;
    }

    public List<String> warnings() {
        return warnings;
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    /** Holder for remote metadata so the context stays decoupled from the downloader. */
    public static final class MediaMetadataHolder {
        private String provider;
        private String externalId;
        private String title;
        private String uploader;
        private Long durationMs;
        private String rawJson;
        private int width;
        private int height;

        public String provider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String externalId() {
            return externalId;
        }

        public void setExternalId(String externalId) {
            this.externalId = externalId;
        }

        public String title() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String uploader() {
            return uploader;
        }

        public void setUploader(String uploader) {
            this.uploader = uploader;
        }

        public Long durationMs() {
            return durationMs;
        }

        public void setDurationMs(Long durationMs) {
            this.durationMs = durationMs;
        }

        public String rawJson() {
            return rawJson;
        }

        public void setRawJson(String rawJson) {
            this.rawJson = rawJson;
        }

        public int width() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int height() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }
    }
}
