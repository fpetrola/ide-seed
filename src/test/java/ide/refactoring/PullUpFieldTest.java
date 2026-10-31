package ide.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
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

        assertThat(result.violations()).isEmpty();
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
    void pullUpPrivateFieldUsedInSubclassOpensMinimalVisibility() {
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        public class A {
                        }
                        """,
                "a/B.java", """
                        package a;

                        public class B extends A {

                            private int x;

                            int get() {
                                return x;
                            }
                        }
                        """));

        var result = ws.invoke("pull-up-field", Map.of("field", "a.B#x"));

        assertThat(result.violations()).isEmpty();
        assertThat(result.repairs()).containsExactly("open-visibility a.A#x private -> package");
        assertThat(ws.source("a/A.java")).isEqualTo("""
                package a;

                public class A {

                    int x;
                }
                """);
    }

    @Test
    void pullUpFieldDragsItsInitializerDependency() {
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        public class A {
                        }
                        """,
                "a/B.java", """
                        package a;

                        public class B extends A {

                            private int y = 1;
                            protected int x = y;
                        }
                        """));

        var result = ws.invoke("pull-up-field", Map.of("field", "a.B#x"));

        assertThat(result.violations()).isEmpty();
        assertThat(result.repairs()).containsExactly("pull-up-dependency a.B#y -> a.A");
        assertThat(ws.source("a/A.java")).isEqualTo("""
                package a;

                public class A {

                    private int y = 1;

                    protected int x = y;
                }
                """);
    }
}
