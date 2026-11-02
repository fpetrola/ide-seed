package ide.acceptance.refactoring;

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
        assertThat(result.search().lines()).containsExactly("? pull-up-field(a.B#x)", "  ! pull-up-field");
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

    /** pull-up-field wants the field in the class its type extends: a type that extends nothing has none. */
    @Test
    void aFieldOfATypeThatExtendsNothingHasNowhereToGoUpAndTheRulesSaySo() {
        var ws = Workspace.of(Map.of("a/B.java", """
                package a;

                public class B {
                    protected int x;
                }
                """));

        var result = ws.invoke("pull-up-field", Map.of("field", "a.B#x"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code, Violation::detail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("intent.unreachable", "pull-up-field needs superclass(a.B, u)"));
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
        assertThat(result.repairs()).isEmpty(); // the rules saw what the move breaks: the plan mends it
        assertThat(result.steps()).containsExactly(
                ". pull-up-field",
                "  ' move a.B#x -> a.A",
                "  ' set a.A#x visibility = package");
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
