package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeclareAbstractTest {

    @Test
    void declaresTheMethodAbstractInTheSuperclassWhichBecomesAbstract() {
        var ws = Workspace.of(Map.of(
                "a/Shape.java", """
                        package a;

                        public class Shape {
                            String name;
                        }
                        """,
                "a/Circle.java", """
                        package a;

                        public class Circle extends Shape {
                            double r;

                            public double area() {
                                return 3.14 * r * r;
                            }
                        }
                        """));

        var result = ws.invoke("declare-abstract-in-superclass", Map.of("method", "a.Circle#area()"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Shape.java")).isEqualTo("""
                package a;

                public abstract class Shape {
                    String name;

                    public abstract double area();
                }
                """);
        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void abortsWhenTheSuperclassIsInstantiated() {
        var ws = Workspace.of(Map.of(
                "a/Shape.java", """
                        package a;

                        public class Shape {
                            static Shape any() {
                                return new Shape();
                            }
                        }
                        """,
                "a/Circle.java", """
                        package a;

                        public class Circle extends Shape {
                            public double area() {
                                return 1;
                            }
                        }
                        """));

        var result = ws.invoke("declare-abstract-in-superclass", Map.of("method", "a.Circle#area()"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(v -> v.code()).containsExactly("javac.abstract.cant.be.instantiated");
    }
}
