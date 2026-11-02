package ide.java;

import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A constructor's calls are the {@code new}s that make its objects: its parameters change them as they change calls. */
class ConstructorCallsTest {

    private static final Map<String, String> SOURCES = Map.of("a/Q.java", """
            package a;

            class Q {
                Q(int a, String b) {
                }
            }
            """, "a/User.java", """
            package a;

            class User {
                Q q = new Q(1, "one");

                Q make() {
                    return new Q(2, "two");
                }
            }
            """);

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow(() -> new AssertionError("no element " + address)).id();
    }

    @Test
    void aParameterAddedIsPassedByEveryNew() {
        var projection = JavaProjection.of(SOURCES);

        projection.apply(new Primitive.Create(Kind.PARAM, id(projection.program(), "a.Q#new(int,String)"), "c", Map.of(Fact.TYPE, "long"), "0L"));

        assertThat(projection.source("a/User.java")).contains("Q q = new Q(1, \"one\", 0L);", "return new Q(2, \"two\", 0L);");
    }

    @Test
    void aParameterMovedOrDeletedIsSoInEveryNew() {
        var projection = JavaProjection.of(SOURCES);
        var p = projection.program();

        projection.apply(new Primitive.Move(id(p, "a.Q#new(int,String)/b"), id(p, "a.Q#new(int,String)"), id(p, "a.Q#new(int,String)/a")));
        projection.apply(new Primitive.Delete(id(projection.program(), "a.Q#new(String,int)/a")));

        assertThat(projection.source("a/User.java")).contains("Q q = new Q(\"one\");", "return new Q(\"two\");");
    }

    @Test
    void aSuperOrThisCallIsACallOfItsConstructorAndPassesWhatANewParameterTakes() {
        var projection = JavaProjection.of(Map.of("a/Q.java", """
                package a;

                class Q {
                    Q(int a) {
                    }
                }
                """, "a/R.java", """
                package a;

                class R extends Q {
                    R() {
                        this(1);
                    }

                    R(int a) {
                        super(a);
                    }
                }
                """));
        var p = projection.program();

        assertThat(p.referencesFrom(id(p, "a.R#new()"))).extracting(r -> p.element(r.target()).qualified() + " " + r.mode().label())
                .containsExactly("a.R#new(int) call");
        assertThat(p.referencesFrom(id(p, "a.R#new(int)"))).extracting(r -> p.element(r.target()).qualified() + " " + r.mode().label())
                .containsExactly("a.Q#new(int) call", "a.R#new(int)/a read");

        projection.apply(new Primitive.Create(Kind.PARAM, id(p, "a.Q#new(int)"), "b", Map.of(Fact.TYPE, "long"), "0L"));

        assertThat(projection.source("a/R.java")).contains("super(a, 0L);", "this(1);");
    }
}
