package com.lectureprocessor.codereview;

import java.util.List;

import com.lectureprocessor.assistant.DocSearchResult;
import com.lectureprocessor.codereview.code.CodeSearchResult;
import com.lectureprocessor.gitea.GiteaChangedFile;

/**
 * Everything the reviewer is allowed to look at, assembled before the model
 * call. Repository content is untrusted; the orchestrator wraps it accordingly.
 */
public record ReviewContext(
        String repository,
        long prNumber,
        String title,
        String baseSha,
        String headSha,
        String diff,
        List<GiteaChangedFile> changedFiles,
        List<CodeSearchResult> codeContext,
        List<DocSearchResult> docContext,
        boolean partial,
        List<String> analyzedFiles,
        List<String> skippedFiles,
        boolean codeIndexAvailable,
        String codeIndexNote) {

    public ReviewContext {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        codeContext = codeContext == null ? List.of() : List.copyOf(codeContext);
        docContext = docContext == null ? List.of() : List.copyOf(docContext);
        analyzedFiles = analyzedFiles == null ? List.of() : List.copyOf(analyzedFiles);
        skippedFiles = skippedFiles == null ? List.of() : List.copyOf(skippedFiles);
    }
}
