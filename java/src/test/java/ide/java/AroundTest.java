package ide.java;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a place of a file is inside, innermost first: what an editor selects as it widens its selection. */
class AroundTest {

    @Test
    void aNameIsInsideItsExpressionItsStatementItsBlockItsMethodAndItsClass() {
        var projection = JavaProjection.of(Map.of("a/A.java", """
                package a;

                class A {
                    int twice(int x) {
                        return x * 2 + 1;
                    }
                }
                """));

        assertThat(projection.around("a/A.java", 5, 16)).extracting(s -> s.line() + ":" + s.column() + "-" + s.endLine() + ":" + s.endColumn())
                .startsWith("5:16-5:16", "5:16-5:20", "5:16-5:24", "5:9-5:25", "4:22-6:5", "4:5-6:5", "3:1-7:1");
    }

    @Test
    void anElementIsDeclaredOverAWholeStretchFromItsModifiersToItsEnd() {
        var projection = JavaProjection.of(Map.of("a/A.java", """
                package a;

                class A {
                    /** Twice and one. */
                    public int twice(int x) {
                        return x * 2 + 1;
                    }

                    int y, z;
                }
                """));
        var program = projection.program();

        assertThat(projection.declared(program.find("a.A#twice(int)").orElseThrow().id())).map(s -> s.line() + "-" + s.endLine()).hasValue("5-7");
        assertThat(projection.declared(program.find("a.A#z").orElseThrow().id())).map(s -> s.line() + "-" + s.endLine()).hasValue("9-9");
        assertThat(projection.declared(program.find("a.A").orElseThrow().id())).map(s -> s.line() + "-" + s.endLine()).hasValue("3-10");
    }
}
