package com.lectureprocessor.fileagent.workspace;

import java.time.Instant;

/** Non-content metadata for one workspace file. */
public record FileMetadata(
        String workspace,
        String path,
        long sizeBytes,
        String contentType,
        Instant lastModified,
        String sha256,
        boolean protectedFile,
        boolean readOnly) {
}
