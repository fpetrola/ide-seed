package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A member moves up, and the same member in the superclass's other subclasses goes into it: one where there were two. */
class PullUpUnifyingTest {

    private static final String SHAPE = """
            package a;

            public class Shape {
            }
            """;

    @Test
    void theSameFieldInTwoSubclassesBecomesOneFieldOfTheirSuperclass() {
        var ws = Workspace.of(Map.of("a/Shape.java", SHAPE,
                "a/Circle.java", """
                        package a;

                        public class Circle extends Shape {
                            String label;
                        }
                        """,
                "a/Square.java", """
                        package a;

                        public class Square extends Shape {
                            String label;

                            String title() {
                                return label;
                            }
                        }
                        """,
                "a/Board.java", """
                        package a;

                        class Board {
                            String show(Square square) {
                                return square.label;
                            }
                        }
                        """));

        var result = ws.invoke("pull-up-unifying", Map.of("member", "a.Circle#label"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Shape.java")).isEqualTo("""
                package a;

                public class Shape {

                    String label;
                }
                """);
        assertThat(ws.source("a/Circle.java")).doesNotContain("label");
        assertThat(ws.source("a/Square.java")).isEqualTo("""
                package a;

                public class Square extends Shape {
                    String title() {
                        return label;
                    }
                }
                """);
        assertThat(ws.model()).contains("a.Board#show(Square) -> a.Shape#label (read) in [1]",
                "a.Square#title() -> a.Shape#label (read) in [1]");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void theSameMethodInTwoSubclassesBecomesOneMethodOfTheirSuperclass() {
        var ws = Workspace.of(Map.of("a/Shape.java", SHAPE,
                "a/Circle.java", """
                        package a;

                        public class Circle extends Shape {
                            public String kind() {
                                return "shape";
                            }
                        }
                        """,
                "a/Square.java", """
                        package a;

                        public class Square extends Shape {
                            public String kind() {
                                return "shape";
                            }
                        }
                        """,
                "a/Board.java", """
                        package a;

                        class Board {
                            String show(Square square) {
                                return square.kind();
                            }
                        }
                        """));

        var result = ws.invoke("pull-up-unifying", Map.of("member", "a.Circle#kind()"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Shape.java")).contains("public String kind() {");
        assertThat(ws.source("a/Circle.java")).doesNotContain("kind");
        assertThat(ws.source("a/Square.java")).doesNotContain("kind");
        assertThat(ws.model()).contains("a.Board#show(Square) -> a.Shape#kind() (call) in [1]");
        assertThat(ws.errors()).isEmpty();
    }

    /** A sibling's method with another body is not the same method: it stays, and overrides the one moved up. */
    @Test
    void aMethodThatOnlySharesTheSignatureStaysAndOverridesIt() {
        var ws = Workspace.of(Map.of("a/Shape.java", SHAPE,
                "a/Circle.java", """
                        package a;

                        public class Circle extends Shape {
                            public String kind() {
                                return "round";
                            }
                        }
                        """,
                "a/Square.java", """
                        package a;

                        public class Square extends Shape {
                            public String kind() {
                                return "square";
                            }
                        }
                        """));

        var result = ws.invoke("pull-up-unifying", Map.of("member", "a.Circle#kind()"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Shape.java")).contains("return \"round\";");
        assertThat(ws.source("a/Square.java")).contains("return \"square\";");
        assertThat(ws.errors()).isEmpty();
    }
}
