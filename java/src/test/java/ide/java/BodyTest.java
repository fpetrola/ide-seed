package ide.java;

import ide.program.Body;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Statements in no language, written in Java: fields and parameters by name, a field qualified where a parameter of
 * the same name hides it, and a new object by its type's simple name, imported where it must be.
 */
class BodyTest {

    private static final String P = """
            package a;

            public class P {
                int x;
                static int count;

                public P(int x) {
                    this.x = x;
                }

                void setX(int x) {
                }

                void setCount(int count) {
                }

                int get() {
                    return 0;
                }
            }
            """;

    private static String name(Program program, String address) {
        return Body.name(id(program, address));
    }

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow(() -> new AssertionError("no element " + address)).id();
    }

    /** A statement said in no language, at the end of a method's body, in place of the one it has if {@code instead}. */
    private static void say(JavaProjection projection, String method, String said, boolean instead) {
        var p = projection.program();
        if (instead) projection.apply(new Primitive.Delete(id(p, method + "[1]")));
        projection.apply(new Primitive.Create(Kind.STATEMENT, id(projection.program(), method), "", Map.of(Fact.BODY, said), null));
    }

    @Test
    void theDefaultIsWhatItsTypeStartsWithAndAVoidMethodHasNoneToAnswer() {
        var projection = JavaProjection.of(Map.of("a/P.java", P));
        var p = projection.program();

        say(projection, "a.P#get()", "return default", true);

        assertThat(projection.source("a/P.java")).contains("""
                    int get() {
                        return 0;
                    }
                """);
        assertThatThrownBy(() -> projection.apply(new Primitive.Create(Kind.STATEMENT, id(p, "a.P#setX(int)"), "",
                Map.of(Fact.BODY, "return default"), null))).isInstanceOf(PrimitiveRejected.class);
    }

    @Test
    void aFieldHiddenByAParameterIsWrittenThroughItsReceiver() {
        var projection = JavaProjection.of(Map.of("a/P.java", P));
        var p = projection.program();

        say(projection, "a.P#setX(int)", name(p, "a.P#x") + " = " + name(p, "a.P#setX(int)/x"), false);
        say(projection, "a.P#setCount(int)", name(p, "a.P#count") + " = " + name(p, "a.P#setCount(int)/count"), false);
        say(projection, "a.P#get()", "return " + name(p, "a.P#x"), true);

        assertThat(projection.source("a/P.java")).contains("""
                    void setX(int x) {
                        this.x = x;
                    }

                    void setCount(int count) {
                        P.count = count;
                    }

                    int get() {
                        return x;
                    }
                """);
    }

    @Test
    void aNewObjectNamesItsTypeAsTheFileCan() {
        var projection = JavaProjection.of(Map.of("a/P.java", P, "b/Shop.java", """
                package b;

                class Shop {
                    Object make(int size) {
                        return null;
                    }
                }
                """));
        var p = projection.program();

        say(projection, "b.Shop#make(int)", "return new " + name(p, "a.P#new(int)") + "(" + name(p, "b.Shop#make(int)/size") + ")", true);

        assertThat(projection.source("b/Shop.java")).isEqualTo("""
                package b;

                import a.P;

                class Shop {
                    Object make(int size) {
                        return new P(size);
                    }
                }
                """);
    }
}
