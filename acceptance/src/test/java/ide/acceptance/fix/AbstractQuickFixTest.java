package ide.acceptance.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A class missing what its supertypes leave abstract, from what Java says (java.rules): a class that is not abstract
 * implements every method it inherits without a body, and nothing makes objects of an abstract class. Making it
 * abstract is a promise broken on purpose, and only where nothing makes its objects.
 */
class AbstractQuickFixTest {

    private static final Map<String, String> SHAPES = Map.of("a/Shape.java", """
            package a;

            abstract class Shape {
                abstract int side();
            }
            """, "a/Square.java", """
            package a;

            class Square extends Shape {
                Square() {
                }
            }
            """);

    @Test
    void aClassNothingMakesObjectsOfMayBecomeAbstract() {
        var ws = Workspace.of(SHAPES);

        assertThat(ws.fixes("a/Square.java", 3)).containsExactly("implement methods of Square", "make Square abstract");
    }

    @Test
    void aClassSomethingMakesObjectsOfStaysConcrete() {
        var sources = new java.util.HashMap<>(SHAPES);
        sources.put("a/Box.java", """
                package a;

                class Box {
                    Shape shape = new Square();
                }
                """);
        var ws = Workspace.of(sources);

        assertThat(ws.fixes("a/Square.java", 3)).containsExactly("implement methods of Square");
        assertThat(ws.why("a/Square.java", 3).lines()).anyMatch(line -> line.startsWith("  X make Square abstract: breaks no-new-abstract(a.Box#shape, a.Square#new())"));
    }
}
