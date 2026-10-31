package ide.refactor;

import ide.coherence.Violation;
import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PullUpMethodTest {

    private static final String A = """
            package a;

            public class A {
            }
            """;

    @Test
    void pullsUpMethodKeepingInternalAndExternalCallsBound() {
        var ws = Workspace.of(Map.of(
                "a/A.java", A,
                "a/B.java", """
                        package a;

                        public class B extends A {

                            public int twice(int v) {
                                return v * 2;
                            }

                            int four() {
                                return twice(2);
                            }
                        }
                        """,
                "c/C.java", """
                        package c;

                        import a.B;

                        class C {
                            int six() {
                                return new B().twice(3);
                            }
                        }
                        """));

        var result = ws.invoke("pull-up-method", Map.of("method", "a.B#twice(int)"));

        assertThat(result.violations()).isEmpty();
        assertThat(result.applied()).isTrue();
        assertThat(ws.source("a/A.java")).isEqualTo("""
                package a;

                public class A {

                    public int twice(int v) {
                        return v * 2;
                    }
                }
                """);
        assertThat(ws.source("a/B.java")).isEqualTo("""
                package a;

                public class B extends A {

                    int four() {
                        return twice(2);
                    }
                }
                """);
    }

    @Test
    void methodUsingSubclassStateAborts() {
        var b = """
                package a;

                public class B extends A {

                    int count;

                    int total() {
                        return count * 2;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/A.java", A, "a/B.java", b));

        var result = ws.invoke("pull-up-method", Map.of("method", "a.B#total()"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("binding.unresolved");
        assertThat(result.violations().getFirst().detail()).contains("a.A#total()", "a.B#count");
        assertThat(ws.source("a/B.java")).isEqualTo(b);
    }

    @Test
    void methodThatWouldBeOverriddenBySiblingAborts() {
        var ws = Workspace.of(Map.of(
                "a/A.java", A,
                "a/B.java", """
                        package a;

                        public class B extends A {

                            String name() {
                                return "b";
                            }
                        }
                        """,
                "a/C.java", """
                        package a;

                        public class C extends A {

                            String name() {
                                return "c";
                            }
                        }
                        """));

        var result = ws.invoke("pull-up-method", Map.of("method", "a.B#name()"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("dispatch.new-override");
        assertThat(result.violations().getFirst().detail()).contains("a.C#name()", "a.A#name()");
        assertThat(ws.source("a/A.java")).isEqualTo(A);
    }
}
