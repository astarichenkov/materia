package com.lectureprocessor.fileagent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.lectureprocessor.assistant.ProjectRootResolver;
import com.lectureprocessor.fileagent.FileAgentException;
import com.lectureprocessor.fileagent.FileAgentProperties;
import com.lectureprocessor.fileagent.FileWorkspace;
import com.lectureprocessor.storage.FileStorageService;

/**
 * Resolves the allowed roots for the file agent. Each workspace has exactly one
 * root; tasks never switch roots and cannot address a path belonging to another
 * workspace.
 */
@Service
public class FileWorkspaceResolver {

    private static final Pattern LECTURE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final FileAgentProperties properties;
    private final ProjectRootResolver projectRoot;
    private final FileStorageService storage;

    public FileWorkspaceResolver(FileAgentProperties properties, ProjectRootResolver projectRoot,
            FileStorageService storage) {
        this.properties = properties;
        this.projectRoot = projectRoot;
        this.storage = storage;
    }

    public FileWorkspaceRef resolve(FileWorkspace kind, String lectureId) {
        if (kind == null) {
            throw new FileAgentException("Workspace не задан");
        }
        if (kind == FileWorkspace.PROJECT) {
            Path root = projectRoot();
            return ref(FileWorkspace.PROJECT, null, root);
        }
        if (!properties.lectureWorkspaceEnabled()) {
            throw new FileAgentException("Workspace лекции отключён "
                    + "(app.file-agent.lecture-workspace-enabled=false)");
        }
        if (lectureId == null || !LECTURE_ID.matcher(lectureId).matches()) {
            throw new FileAgentException("Некорректный идентификатор лекции");
        }
        Path root;
        try {
            root = storage.lectureDir(lectureId);
        } catch (RuntimeException e) {
            throw new FileAgentException("Не удалось открыть workspace лекции: " + e.getMessage(), e);
        }
        return ref(FileWorkspace.LECTURE, lectureId, root);
    }

    public FileWorkspaceRef resolve(String workspace, String lectureId) {
        return resolve(FileWorkspace.from(workspace), lectureId);
    }

    private Path projectRoot() {
        String configured = properties.projectRoot();
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.strip()).toAbsolutePath().normalize();
        }
        return projectRoot.resolve().toAbsolutePath().normalize();
    }

    private static FileWorkspaceRef ref(FileWorkspace kind, String lectureId, Path root) {
        try {
            if (!Files.isDirectory(root)) {
                throw new FileAgentException("Workspace не существует: " + root.getFileName());
            }
            Path real = root.toRealPath();
            return new FileWorkspaceRef(kind, lectureId, root, real);
        } catch (IOException e) {
            throw new FileAgentException("Workspace недоступен: " + e.getMessage(), e);
        }
    }
}
