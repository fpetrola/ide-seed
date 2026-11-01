package ide.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An answer, written as JSON by the capability, read as text by a person. */
class TextTest {

    @Test
    void textIsItselfAndAListIsOneLineEach() {
        assertThat(Text.of("\"package a;\\n\\nclass P {\\n}\\n\"")).isEqualTo("package a;\n\nclass P {\n}\n");
        assertThat(Text.of("[\"a/P.java\",\"b/U.java\"]")).isEqualTo("a/P.java\nb/U.java\n");
        assertThat(Text.of("[]")).isEqualTo("");
    }

    @Test
    void anObjectIsOneLinePerKeyWithItsListsIndented() {
        assertThat(Text.of("{\"applied\":true,\"violations\":[],\"steps\":[\"' rename a.P#x -> y\"],\"diff\":\"-x\\n+y\\n\"}"))
                .isEqualTo("""
                        applied: true
                        violations: none
                        steps:
                          ' rename a.P#x -> y
                        diff:
                          -x
                          +y
                        """);
        assertThat(Text.of("[{\"path\":\"a/U.java\",\"line\":4}]")).isEqualTo("path: a/U.java, line: 4\n");
    }
}
