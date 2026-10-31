package ide.refactor;

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

        ws.invoke("pull-up-field", Map.of("field", "a.B#x"));

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
}
