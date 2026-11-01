package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollapseHierarchyTest {

    private static final String ANIMAL = """
            package a;

            public class Animal {
                String name;
            }
            """;
    private static final String KENNEL = """
            package b;

            import a.Dog;

            class Kennel {
                Dog dog = new Dog();

                String noise() {
                    return dog.bark();
                }
            }
            """;

    @Test
    void membersGoUpUsesTakeTheSuperclassAndTheClassIsGone() {
        var ws = Workspace.of(Map.of("a/Animal.java", ANIMAL, "b/Kennel.java", KENNEL, "a/Dog.java", """
                package a;

                public class Dog extends Animal {
                    int legs = 4;

                    public String bark() {
                        return name + " woof";
                    }
                }
                """));

        var result = ws.invoke("collapse-hierarchy", Map.of("type", "a.Dog"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.files()).doesNotContain("a/Dog.java");
        assertThat(ws.source("a/Animal.java")).contains("int legs = 4;", "public String bark() {");
        assertThat(ws.source("b/Kennel.java")).isEqualTo("""
                package b;

                import a.Animal;

                class Kennel {
                    Animal dog = new Animal();

                    String noise() {
                        return dog.bark();
                    }
                }
                """);
        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void aNestedClassCollapsesIntoItsSuperclassToo() {
        var ws = Workspace.of(Map.of("a/Shapes.java", """
                package a;

                public class Shapes {
                    static class Shape {
                        int sides;
                    }

                    static class Square extends Shape {
                        int side;
                    }

                    int count(Square square) {
                        return square.side + square.sides;
                    }
                }
                """));

        var result = ws.invoke("collapse-hierarchy", Map.of("type", "a.Shapes.Square"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Shapes.java")).isEqualTo("""
                package a;

                public class Shapes {
                    static class Shape {
                        int sides;

                        int side;
                    }

                    int count(Shape square) {
                        return square.side + square.sides;
                    }
                }
                """);
        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void aClassWithConstructorsHasNoEquivalentInItsSuperclass() {
        var ws = Workspace.of(Map.of("a/Animal.java", ANIMAL, "b/Kennel.java", KENNEL, "a/Dog.java", """
                package a;

                public class Dog extends Animal {
                    public Dog() {
                        name = "dog";
                    }

                    public String bark() {
                        return name;
                    }
                }
                """));

        var result = ws.invoke("collapse-hierarchy", Map.of("type", "a.Dog"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("refactoring.precondition");
        assertThat(result.violations()).extracting(Violation::detail)
                .containsExactly("a.Dog has constructors; a.Animal has no equivalent"); // elements by address, not id
    }
}
