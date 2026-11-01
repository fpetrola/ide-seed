package ide.java;

import ide.program.Body;
import ide.program.ElementId;
import ide.program.Primitive;
import ide.program.Program;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A body in no language, written in Java: fields and parameters by name, a field qualified where a parameter of the
 * same name hides it, and a new object by its type's simple name, imported where it must be.
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

    @Test
    void theDefaultIsWhatItsTypeStartsWithAndNothingForVoid() {
        var projection = JavaProjection.of(Map.of("a/P.java", P));
        var p = projection.program();

        projection.apply(new Primitive.Set(id(p, "a.P#get()"), "body", "return default"));
        projection.apply(new Primitive.Set(id(p, "a.P#setX(int)"), "body", "return default"));

        assertThat(projection.source("a/P.java")).contains("""
                    void setX(int x) {
                    }
                """, """
                    int get() {
                        return 0;
                    }
                """);
    }

    @Test
    void aFieldHiddenByAParameterIsWrittenThroughItsReceiver() {
        var projection = JavaProjection.of(Map.of("a/P.java", P));
        var p = projection.program();

        projection.apply(new Primitive.Set(id(p, "a.P#setX(int)"), "body", name(p, "a.P#x") + " = " + name(p, "a.P#setX(int)/x")));
        projection.apply(new Primitive.Set(id(p, "a.P#setCount(int)"), "body",
                name(p, "a.P#count") + " = " + name(p, "a.P#setCount(int)/count")));
        projection.apply(new Primitive.Set(id(p, "a.P#get()"), "body", "return " + name(p, "a.P#x")));

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

        projection.apply(new Primitive.Set(id(p, "b.Shop#make(int)"), "body",
                "return new " + name(p, "a.P#new(int)") + "(" + name(p, "b.Shop#make(int)/size") + ")"));

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
