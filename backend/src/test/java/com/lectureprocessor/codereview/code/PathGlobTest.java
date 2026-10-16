package com.lectureprocessor.codereview.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Glob exclusions used to keep secrets and build output out of the code index. */
class PathGlobTest {

    @Test
    void excludesSecretsAndBuildOutput() {
        List<String> patterns = List.of("**/.env", "**/*.key", "**/target/**", "**/data/**");
        assertThat(PathGlob.matchesAny("backend/.env", patterns)).isTrue();
        assertThat(PathGlob.matchesAny("config/master.key", patterns)).isTrue();
        assertThat(PathGlob.matchesAny("backend/target/classes/A.class", patterns)).isTrue();
        assertThat(PathGlob.matchesAny("data/lecture.db", patterns)).isTrue();
        assertThat(PathGlob.matchesAny("backend/src/main/java/A.java", patterns)).isFalse();
    }

    @Test
    void prefixMatchingSelectsCodePaths() {
        List<String> prefixes = List.of("backend/src", "pom.xml");
        assertThat(PathGlob.startsWithAny("backend/src/main/java/A.java", prefixes)).isTrue();
        assertThat(PathGlob.startsWithAny("pom.xml", prefixes)).isTrue();
        assertThat(PathGlob.startsWithAny("docs/readme.md", prefixes)).isFalse();
    }

    @Test
    void singleStarDoesNotCrossDirectories() {
        assertThat(PathGlob.matches("a/b.txt", "*.txt")).isFalse();
        assertThat(PathGlob.matches("b.txt", "*.txt")).isTrue();
        assertThat(PathGlob.matches("a/b.txt", "**/*.txt")).isTrue();
    }
}
