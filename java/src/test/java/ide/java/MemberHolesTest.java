package ide.java;

import ide.program.ElementId;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Unresolved;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A field or a method a name binds nothing of is a use of the program too, like a type that is nowhere: a retarget
 * binds it. Only what surely binds nothing is one: never a local, a parameter, a type, a package, a case label.
 */
class MemberHolesTest {

    private static final Map<String, String> SOURCES = Map.of(
            "a/A.java", """
                    package a;

                    import java.util.List;
                    import java.util.function.IntUnaryOperator;

                    public class A {
                        int count;
                        Color color;

                        int total(A other, List<String> names) {
                            int local = cuont + other.missing;
                            IntUnaryOperator twice = x -> x * 2;
                            tally = local;
                            switch (color) {
                                case RED -> local++;
                                default -> local--;
                            }
                            if (names instanceof java.util.ArrayList<String> list) local += list.size();
                            java.util.function.Supplier<Integer> size = names::size;
                            local += names.stream().map(n -> n.length()).max(java.util.Comparator.comparingInt(n -> n)).get();
                            return twice.applyAsInt(local) + Math.max(1, 2) + names.size() + sizeOf(names) + java.util.List.of().size();
                        }

                        int length(List<String> names) {
                            return names.size();
                        }
                    }
                    """,
            "a/Color.java", """
                    package a;

                    public enum Color { RED, GREEN }
                    """);

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow(() -> new AssertionError("no element " + address)).id();
    }

    @Test
    void whatSurelyBindsNothingIsAUseThatBindsNothing() {
        var program = JavaProjection.of(SOURCES).program();

        assertThat(program.unresolved()).extracting(u -> u.name() + " " + u.mode().label())
                .containsExactlyInAnyOrder("cuont read", "missing read", "tally write", "sizeOf call");
    }

    @Test
    void aMemberThatBindsNothingIsLookedUpInItsReceiversTypeOrInTheOneItIsWrittenIn() {
        var program = JavaProjection.of(SOURCES).program();

        assertThat(program.unresolved()).extracting(u -> u.name() + " in " + program.element(u.scope()).qualified())
                .containsExactlyInAnyOrder("cuont in a.A", "missing in a.A", "tally in a.A", "sizeOf in a.A");
    }

    @Test
    void aRetargetBindsItAndWritesTheName() {
        var projection = JavaProjection.of(SOURCES);
        var p = projection.program();
        var typo = p.unresolved().stream().filter(u -> u.name().equals("cuont")).map(Unresolved::id).findFirst().orElseThrow();
        var call = p.unresolved().stream().filter(u -> u.name().equals("sizeOf")).map(Unresolved::id).findFirst().orElseThrow();

        projection.apply(new Primitive.Retarget(typo, id(p, "a.A#count")));
        projection.apply(new Primitive.Retarget(call, id(p, "a.A#length(List<String>)")));

        assertThat(projection.source("a/A.java")).contains("int local = count + other.missing;", "+ length(names) +");
        assertThat(projection.program().unresolved()).extracting(Unresolved::name).doesNotContain("cuont", "sizeOf");
    }
}
