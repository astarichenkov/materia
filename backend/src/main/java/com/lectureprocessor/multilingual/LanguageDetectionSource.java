package com.lectureprocessor.multilingual;

/** Where the stored language information came from. */
public enum LanguageDetectionSource {
    /** whisper.cpp reported the language it detected. */
    WHISPER_DETECTED,
    /** Language was forced by the user / request. */
    MANUAL,
    /** Whisper was run with an explicit language and confirmed it. */
    WHISPER_FORCED,
    /** Cheap script-based heuristic (no confidence reported by the tool). */
    HEURISTIC
}
