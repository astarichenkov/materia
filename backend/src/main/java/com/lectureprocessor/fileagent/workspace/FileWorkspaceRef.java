package com.lectureprocessor.fileagent.workspace;

import java.nio.file.Path;

import com.lectureprocessor.fileagent.FileWorkspace;

/**
 * A resolved workspace root. {@code root} is the configured path and
 * {@code realRoot} is its canonical (symlink-free) form used for containment
 * checks.
 */
public record FileWorkspaceRef(FileWorkspace kind, String lectureId, Path root, Path realRoot) {

    public String id() {
        return kind == FileWorkspace.LECTURE ? kind.name() + ":" + lectureId : kind.name();
    }
}
