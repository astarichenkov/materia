package com.lectureprocessor.codereview.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Unified diff parsing: file kinds, hunks and line anchors. */
class UnifiedDiffParserTest {

    @Test
    void parsesModifiedFileWithMultipleHunksAndAnchors() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                index 111..222 100644
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1,4 +1,5 @@
                 class A {
                -    int x = 1;
                +    int x = 1;
                +    int y = 2;
                     void f() {}
                @@ -10,3 +11,4 @@
                     void g() {
                +        log();
                     }
                """;
        List<DiffFile> files = UnifiedDiffParser.parse(diff);
        assertThat(files).hasSize(1);
        DiffFile file = files.getFirst();
        assertThat(file.path()).isEqualTo("src/A.java");
        assertThat(file.changeType()).isEqualTo(DiffFile.ChangeType.MODIFIED);
        assertThat(file.hunks()).hasSize(2);
        // Added lines and context lines are valid anchors; the removed line is not.
        assertThat(file.anchorsLine(2)).isTrue();
        assertThat(file.anchorsLine(3)).isTrue();
        assertThat(file.anchorsLine(4)).isTrue();
        assertThat(file.anchorsLine(12)).isTrue();
        assertThat(file.anchorsLine(1)).isTrue();
    }

    @Test
    void detectsAddedDeletedRenamedAndBinaryFiles() {
        String diff = """
                diff --git a/new.txt b/new.txt
                new file mode 100644
                --- /dev/null
                +++ b/new.txt
                @@ -0,0 +1 @@
                +hello
                diff --git a/old.txt b/old.txt
                deleted file mode 100644
                --- a/old.txt
                +++ /dev/null
                @@ -1 +0,0 @@
                -bye
                diff --git a/a.txt b/b.txt
                similarity index 100%
                rename from a.txt
                rename to b.txt
                diff --git a/img.png b/img.png
                Binary files a/img.png and b/img.png differ
                """;
        List<DiffFile> files = UnifiedDiffParser.parse(diff);
        assertThat(files).hasSize(4);
        assertThat(files.get(0).changeType()).isEqualTo(DiffFile.ChangeType.ADDED);
        assertThat(files.get(0).path()).isEqualTo("new.txt");
        assertThat(files.get(1).changeType()).isEqualTo(DiffFile.ChangeType.DELETED);
        assertThat(files.get(2).changeType()).isEqualTo(DiffFile.ChangeType.RENAMED);
        assertThat(files.get(2).path()).isEqualTo("b.txt");
        assertThat(files.get(3).changeType()).isEqualTo(DiffFile.ChangeType.BINARY);
    }

    @Test
    void emptyDiffProducesNoFiles() {
        assertThat(UnifiedDiffParser.parse("")).isEmpty();
        assertThat(UnifiedDiffParser.parse(null)).isEmpty();
    }
}
