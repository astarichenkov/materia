package com.lectureprocessor.transcription;

import java.nio.file.Path;

import com.lectureprocessor.domain.Transcript;

/**
 * Converts extracted audio into a timestamped transcript.
 */
public interface TranscriptionService {

    Transcript transcribe(Path audio);

    /**
     * Transcribes with an explicit whisper language ({@code auto} enables
     * detection). Implementations that cannot honour the override keep the
     * default behaviour.
     */
    default Transcript transcribe(Path audio, String language) {
        return transcribe(audio);
    }
}
