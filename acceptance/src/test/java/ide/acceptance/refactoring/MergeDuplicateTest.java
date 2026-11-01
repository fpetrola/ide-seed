package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MergeDuplicateTest {

    @Test
    void callsOfTheDuplicateGoToTheKeptMethodAndTheDuplicateIsDeleted() {
        var ws = Workspace.of(Map.of(
                "a/P.java", """
                        package a;

                        public class P {
                            public int unit() {
                                return 1;
                            }

                            public int one() {
                                return 1;
                            }

                            int two() {
                                return unit() + one();
                            }
                        }
                        """,
                "b/U.java", """
                        package b;

                        class U {
                            java.util.function.IntSupplier s = new a.P()::one;
                        }
                        """));

        var result = ws.invoke("merge-duplicate", Map.of("keep", "a.P#unit()", "duplicate", "a.P#one()"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).doesNotContain("int one()").contains("return unit() + unit();");
        assertThat(ws.source("b/U.java")).contains("new a.P()::unit");
        assertThat(ws.diagnostics()).isEmpty();
    }

    /** Its calls already read like calls of the kept method: the duplicate goes, and they bind the method it overrode. */
    @Test
    void anOverrideThatRepeatsWhatItOverridesMergesIntoIt() {
        var ws = Workspace.of(Map.of(
                "a/Animal.java", """
                        package a;

                        public class Animal {
                            public String sound() {
                                return "...";
                            }
                        }
                        """,
                "a/Fish.java", """
                        package a;

                        public class Fish extends Animal {
                            @Override
                            public String sound() {
                                return "...";
                            }
                        }
                        """,
                "b/Tank.java", """
                        package b;

                        import a.Fish;

                        class Tank {
                            String hear(Fish fish) {
                                return fish.sound();
                            }
                        }
                        """));

        var result = ws.invoke("merge-duplicate", Map.of("keep", "a.Animal#sound()", "duplicate", "a.Fish#sound()"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Fish.java")).isEqualTo("""
                package a;

                public class Fish extends Animal {
                }
                """);
        assertThat(ws.source("b/Tank.java")).contains("return fish.sound();");
        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void methodsWithDifferentBodiesAreNotDuplicates() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int one() {
                        return 1;
                    }

                    int two() {
                        return 2;
                    }
                }
                """));

        var result = ws.invoke("merge-duplicate", Map.of("keep", "a.P#one()", "duplicate", "a.P#two()"));

        assertThat(result.violations()).extracting(Violation::code).containsExactly("refactoring.precondition");
    }
}
