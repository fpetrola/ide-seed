package ide.coherence;

import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreexistingProblemsTest {

    @Test
    void accessProblemsThatExistedBeforeAreNeitherReportedNorRepaired() {
        var c = """
                package c;

                class C {
                    int six(a.B b) {
                        return b.twice(3);
                    }
                }
                """;
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        public class A {
                        }
                        """,
                "a/B.java", """
                        package a;

                        public class B extends A {
                            protected int x;

                            int twice(int v) {
                                return v * 2;
                            }
                        }
                        """,
                "c/C.java", c));

        var result = ws.invoke("pull-up-field", Map.of("field", "a.B#x"));

        assertThat(result.violations()).isEmpty();
        assertThat(result.repairs()).isEmpty();
        assertThat(ws.source("a/B.java")).contains("    int twice(int v) {");
    }
}
