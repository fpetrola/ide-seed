package ide.java;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Java as it is written today, up to 21: records, sealed types, patterns, switch expressions, text blocks, annotations. */
class ModernJavaTest {

    private static final Map<String, String> SOURCES = Map.of(
            "a/Shape.java", """
                    package a;

                    public sealed interface Shape permits Circle, Square {

                        static double area(Shape shape) {
                            return switch (shape) {
                                case Circle c when c.radius() > 0 -> Math.PI * c.radius() * c.radius();
                                case Circle c -> 0;
                                case Square s -> s.side() * s.side();
                            };
                        }
                    }
                    """,
            "a/Circle.java", """
                    package a;

                    @Shown
                    public record Circle(double radius) implements Shape {
                        static final Circle UNIT = new Circle(1);
                    }
                    """,
            "a/Square.java", """
                    package a;

                    public record Square(double side) implements Shape {
                    }
                    """,
            "a/Shown.java", """
                    package a;

                    public @interface Shown {
                    }
                    """,
            "a/Report.java", """
                    package a;

                    class Report {
                        String describe(Object o) {
                            if (o instanceof Circle circle) {
                                return \"""
                                        a circle of
                                        \""" + circle.radius();
                            }
                            return o instanceof Square(double side) ? "a square of " + side : "nothing";
                        }

                        Circle unit() {
                            return Circle.UNIT;
                        }

                        int count() {
                            record Pair(int a, int b) {
                            }
                            class Counter {
                                int n = Circle.UNIT.hashCode();
                            }
                            return new Pair(1, 2).a() + new Counter().n;
                        }
                    }
                    """);

    @Test
    void javaUpTo21IsReadAndItsTypesAreTheProgramsOwn() {
        var projection = JavaProjection.of(SOURCES);

        var program = projection.program();

        assertThat(program.outline()).contains("CLASS a.Circle", "CLASS a.Square", "INTERFACE a.Shape", "INTERFACE a.Shown",
                "FIELD a.Circle#UNIT", "a.Circle -> a.Shape (implements)", "a.Report#unit() -> a.Circle (declared)",
                "a.Report#unit() -> a.Circle#UNIT (read) in [1]");
        assertThat(program.unresolved()).isEmpty();
        assertThat(projection.diagnostics()).isEmpty();
    }

    /** A type declared inside a method is the method's, as its locals are: no element of the program. */
    @Test
    void aTypeDeclaredInAMethodIsNoElement() {
        var program = JavaProjection.of(SOURCES).program();

        assertThat(program.outline()).contains("METHOD a.Report#count()", "a.Report#count() -> a.Circle#UNIT (read) in [2]")
                .noneMatch(line -> line.contains("Pair") || line.contains("Counter"));
    }
}
