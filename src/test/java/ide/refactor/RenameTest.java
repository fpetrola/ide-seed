package ide.refactor;

import ide.coherence.Violation;
import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RenameTest {

    @Test
    void renamesMethodAndAllItsCalls() {
        var ws = Workspace.of(Map.of(
                "a/B.java", """
                        package a;

                        public class B {

                            public int twice(int v) {
                                return v * 2;
                            }

                            int four() {
                                return twice(2) + this.twice(0);
                            }
                        }
                        """,
                "c/C.java", """
                        package c;

                        import a.B;

                        class C {
                            int six() {
                                return new B().twice(3); // twice stays in comments
                            }
                        }
                        """));

        var result = ws.invoke("rename", Map.of("element", "a.B#twice(int)", "name", "doubled"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/B.java")).isEqualTo("""
                package a;

                public class B {

                    public int doubled(int v) {
                        return v * 2;
                    }

                    int four() {
                        return doubled(2) + this.doubled(0);
                    }
                }
                """);
        assertThat(ws.source("c/C.java")).isEqualTo("""
                package c;

                import a.B;

                class C {
                    int six() {
                        return new B().doubled(3); // twice stays in comments
                    }
                }
                """);
    }

    @Test
    void renamesFieldAndItsSimpleAndQualifiedUses() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int x;

                    int sum(P other) {
                        int y = x;
                        return y + this.x + other.x;
                    }
                }
                """));

        var result = ws.invoke("rename", Map.of("element", "a.P#x", "name", "total"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).isEqualTo("""
                package a;

                class P {
                    int total;

                    int sum(P other) {
                        int y = total;
                        return y + this.total + other.total;
                    }
                }
                """);
    }

    @Test
    void renameThatShadowsAnInheritedFieldAborts() {
        var b = """
                package a;

                class B extends A {
                    int x;

                    int get() {
                        return y;
                    }
                }
                """;
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        class A {
                            int y;
                        }
                        """,
                "a/B.java", b));

        var result = ws.invoke("rename", Map.of("element", "a.B#x", "name", "y"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("binding.rebound");
        assertThat(result.violations().getFirst().detail()).contains("a.B#get()", "a.B#y", "a.A#y");
        assertThat(ws.source("a/B.java")).isEqualTo(b);
    }

    @Test
    void renameOfOverridingMethodAborts() {
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        class A {
                            String name() {
                                return "a";
                            }
                        }
                        """,
                "a/B.java", """
                        package a;

                        class B extends A {
                            String name() {
                                return "b";
                            }
                        }
                        """));

        var result = ws.invoke("rename", Map.of("element", "a.B#name()", "name", "label"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("dispatch.lost-override");
    }

    @Test
    void renameMethodFamilyRenamesOverridesUpAndDownAndAllCalls() {
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        class A {
                            String name() {
                                return "a";
                            }
                        }
                        """,
                "a/B.java", """
                        package a;

                        class B extends A {
                            String name() {
                                return "b";
                            }
                        }
                        """,
                "a/C.java", """
                        package a;

                        class C extends B {
                            String name() {
                                return "c" + super.name();
                            }

                            String all(A a, B b) {
                                return a.name() + b.name();
                            }
                        }
                        """));

        var result = ws.invoke("rename-method-family", Map.of("method", "a.B#name()", "name", "label"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/A.java")).contains("String label() {");
        assertThat(ws.source("a/B.java")).contains("String label() {");
        assertThat(ws.source("a/C.java")).isEqualTo("""
                package a;

                class C extends B {
                    String label() {
                        return "c" + super.label();
                    }

                    String all(A a, B b) {
                        return a.label() + b.label();
                    }
                }
                """);
    }
}
