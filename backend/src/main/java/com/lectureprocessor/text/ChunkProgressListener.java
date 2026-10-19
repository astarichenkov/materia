package com.lectureprocessor.text;

/**
 * Receives chunk-level progress so the UI can show real "7 из 18 фрагментов"
 * instead of an invented percentage. It is deliberately tiny and side-effect
 * free for tests.
 */
@FunctionalInterface
public interface ChunkProgressListener {

    void onProgress(int completed, int total);

    ChunkProgressListener NONE = (completed, total) -> {
    };
}
