package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
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
    void methodUsingSubclassStateDragsTheFieldUp() {
        var ws = Workspace.of(Map.of("a/A.java", A, "a/B.java", """
                package a;

                public class B extends A {

                    int count;

                    int total() {
                        return count * 2;
                    }
                }
                """));

        var result = ws.invoke("pull-up-method", Map.of("method", "a.B#total()"));

        assertThat(result.violations()).isEmpty();
        assertThat(result.repairs()).containsExactly("pull-up-dependency a.B#count -> a.A");
        assertThat(ws.source("a/A.java")).isEqualTo("""
                package a;

                public class A {

                    int total() {
                        return count * 2;
                    }

                    int count;
                }
                """);
        assertThat(ws.source("a/B.java")).isEqualTo("""
                package a;

                public class B extends A {
                }
                """);
    }

    @Test
    void dependencyCollidingWithSuperclassMemberAborts() {
        var a = """
                package a;

                public class A {

                    int count = 7;
                }
                """;
        var b = """
                package a;

                public class B extends A {

                    int count;

                    int total() {
                        return this.count * 2;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/A.java", a, "a/B.java", b));

        var result = ws.invoke("pull-up-method", Map.of("method", "a.B#total()"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("binding.rebound");
        assertThat(result.violations().getFirst().detail()).contains("a.A#total()", "a.B#count", "a.A#count");
        assertThat(ws.source("a/A.java")).isEqualTo(a);
        assertThat(ws.source("a/B.java")).isEqualTo(b);
    }

    @Test
    /**
     * A sibling's method of the same signature overrides the method moved up, and that changes no dispatch: the calls
     * of the moved method are made on the type it came from, which the sibling is not.
     */
    void aSiblingsMethodOfTheSameSignatureOverridesTheMethodMovedUp() {
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

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/A.java")).contains("return \"b\";");
        assertThat(ws.source("a/C.java")).contains("return \"c\";");
        assertThat(ws.errors()).isEmpty();
    }
}
