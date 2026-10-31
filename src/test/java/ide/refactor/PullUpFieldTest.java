package ide.refactor;

import ide.coherence.Violation;
import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PullUpFieldTest {

    @Test
    void pullUpProtectedField() {
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        public class A {
                        }
                        """,
                "a/B.java", """
                        package a;

                        // B keeps its comment
                        public class B extends A {

                            protected int x;

                            int get() {
                                return x;
                            }
                        }
                        """));

        var result = ws.invoke("pull-up-field", Map.of("field", "a.B#x"));

        assertThat(result.applied()).isTrue();
        assertThat(ws.source("a/A.java")).isEqualTo("""
                package a;

                public class A {

                    protected int x;
                }
                """);
        assertThat(ws.source("a/B.java")).isEqualTo("""
                package a;

                // B keeps its comment
                public class B extends A {

                    int get() {
                        return x;
                    }
                }
                """);
    }

    @Test
    void pullUpPrivateFieldUsedInSubclassAbortsWithAccessViolation() {
        var a = """
                package a;

                public class A {
                }
                """;
        var b = """
                package a;

                public class B extends A {

                    private int x;

                    int get() {
                        return x;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/A.java", a, "a/B.java", b));

        var result = ws.invoke("pull-up-field", Map.of("field", "a.B#x"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations())
                .extracting(Violation::code)
                .containsExactly("access.insufficient");
        assertThat(result.violations().getFirst().detail()).contains("a.B", "x");
        assertThat(ws.source("a/A.java")).isEqualTo(a);
        assertThat(ws.source("a/B.java")).isEqualTo(b);
    }
}
