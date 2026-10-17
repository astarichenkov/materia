package com.lectureprocessor.fileagent.workspace;

import java.nio.file.Path;

/** A path validated against one workspace, expressed relative and absolute. */
public record ResolvedFile(FileWorkspaceRef workspace, String relativePath, Path absolutePath) {
}
