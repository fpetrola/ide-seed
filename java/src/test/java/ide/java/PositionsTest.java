package ide.java;

import ide.program.ElementId;
import ide.program.Program;
import ide.projection.Location;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Where elements are in the text, both ways: a declaration's name, and what the name under a caret means. */
class PositionsTest {

    private static final Map<String, String> SOURCES = Map.of(
            "a/A.java", """
                    package a;

                    public class A {
                        int x;

                        int m(int p) {
                            return x + p;
                        }
                    }
                    """,
            "a/B.java", """
                    package a;

                    class B {
                        int n(A a) {
                            return a.m(1);
                        }
                    }
                    """);

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow(() -> new AssertionError("no element " + address)).id();
    }

    @Test
    void aDeclarationIsWhereItsNameIs() {
        var projection = JavaProjection.of(SOURCES);
        var p = projection.program();

        assertThat(projection.locate(id(p, "a.A"))).contains(new Location("a/A.java", 3, 14));
        assertThat(projection.locate(id(p, "a.A#x"))).contains(new Location("a/A.java", 4, 9));
        assertThat(projection.locate(id(p, "a.A#m(int)/p"))).contains(new Location("a/A.java", 6, 15));
        assertThat(projection.locate(id(p, "a"))).isEmpty();
    }

    @Test
    void aNameUnderTheCaretIsWhatItDeclaresOrWhatItUses() {
        var projection = JavaProjection.of(SOURCES);
        var p = projection.program();

        assertThat(projection.elementAt("a/A.java", 7, 16)).contains(id(p, "a.A#x")); // return x + p
        assertThat(projection.elementAt("a/B.java", 5, 18)).contains(id(p, "a.A#m(int)")); // a.m(1): on m
        assertThat(projection.elementAt("a/B.java", 5, 16)).isEmpty(); // a.m(1): on a, a parameter, used in its body
        assertThat(projection.elementAt("a/B.java", 4, 9)).contains(id(p, "a.B#n(A)")); // its name
        assertThat(projection.elementAt("a/B.java", 4, 11)).contains(id(p, "a.A")); // the type of its parameter
        assertThat(projection.elementAt("a/B.java", 4, 13)).contains(id(p, "a.B#n(A)/a")); // the parameter
        assertThat(projection.elementAt("a/A.java", 2, 1)).isEmpty();
    }
}
