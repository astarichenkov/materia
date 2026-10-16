package com.lectureprocessor.codereview.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Structural Java chunking: class and method boundaries with line ranges. */
class JavaCodeChunkerTest {

    private static final String SOURCE = """
            package com.example;

            import java.util.List;

            public class Service {
                private final List<String> items;

                public Service(List<String> items) {
                    this.items = items;
                }

                public String find(String id) {
                    if (id == null) {
                        return null;
                    }
                    return items.stream().filter(id::equals).findFirst().orElse(null);
                }
            }
            """;

    @Test
    void producesHeaderClassAndMethodChunks() {
        List<CodeSection> sections = JavaCodeChunker.chunk(SOURCE, 4000);
        assertThat(sections).isNotEmpty();
        assertThat(sections).anySatisfy(section -> {
            assertThat(section.chunkType()).isEqualTo("FILE");
            assertThat(section.text()).contains("package com.example;");
        });
        assertThat(sections).anySatisfy(section -> {
            assertThat(section.chunkType()).isEqualTo("CLASS");
            assertThat(section.symbol()).contains("Service");
        });
        assertThat(sections).anySatisfy(section -> {
            assertThat(section.chunkType()).isEqualTo("METHOD");
            assertThat(section.text()).contains("public String find");
        });
        // Line ranges are 1-based and ordered.
        sections.forEach(section -> assertThat(section.startLine()).isLessThanOrEqualTo(section.endLine()));
    }

    @Test
    void largeMethodIsWindowedWithinMaxChars() {
        StringBuilder body = new StringBuilder("public class Big {\n  void f() {\n");
        for (int i = 0; i < 500; i++) {
            body.append("    int x").append(i).append(" = ").append(i).append(";\n");
        }
        body.append("  }\n}\n");
        List<CodeSection> sections = JavaCodeChunker.chunk(body.toString(), 600);
        assertThat(sections).isNotEmpty();
        assertThat(sections).allSatisfy(section ->
                assertThat(section.text().length()).isLessThan(2500));
    }

    @Test
    void emptySourceProducesNoChunks() {
        assertThat(JavaCodeChunker.chunk("", 1000)).isEmpty();
        assertThat(JavaCodeChunker.chunk(null, 1000)).isEmpty();
    }
}
