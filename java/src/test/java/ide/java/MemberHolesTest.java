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
                            Shape shape = null;
                            local += shape.toString().length() + shape.getClass().hashCode() + Color.values().length + shape.side()
                                    + new Square(1).side() + Color.valueOf("RED").ordinal();
                            return twice.applyAsInt(local) + Math.max(1, 2) + names.size() + sizeOf(names) + java.util.List.of().size();
                        }

                        int length(List<String> names) {
                            return names.size();
                        }
                    }
                    """,
            "a/Shape.java", """
                    package a;

                    interface Shape {
                        int side();
                    }
                    """,
            "a/Square.java", """
                    package a;

                    record Square(int side) {
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
    void aUseThatBindsNothingSaysWhatItsPlaceExpectsAndACallWhatItPasses() {
        var projection = JavaProjection.of(Map.of("a/B.java", """
                package a;

                import java.util.List;

                class B {
                    long total(List<String> names, int limit) {
                        log(names, limit, "done", limit + 1, new java.util.Date());
                        String title = describe(this);
                        boolean ok = count > 0;
                        ratio = 0.5;
                        return measure(names.size());
                    }
                }
                """));

        assertThat(projection.program().unresolved()).extracting(u -> u.name() + " " + projection.needs(u.id()).orElseThrow().expected()
                        + " " + projection.needs(u.id()).orElseThrow().arguments().stream().map(a -> a.name() + " " + a.type()).toList())
                .containsExactlyInAnyOrder(
                        "log void [names List<String>, limit int, string String, i int, date java.util.Date]",
                        "describe String [b B]",
                        "count int []",
                        "ratio double []",
                        "measure long [size int]");
    }

    @Test
    void aNewOfAWorkspaceTypeWithNoConstructorTakingWhatItPassesIsAUseThatBindsNothing() {
        var projection = JavaProjection.of(Map.of("a/C.java", """
                package a;

                class C {
                    C(String name) {
                    }

                    static C make(int size) {
                        new StringBuilder(size);
                        new C("named");
                        return new C(size, "big");
                    }
                }
                """));
        var program = projection.program();

        assertThat(program.unresolved()).extracting(u -> u.name() + " " + u.mode().label() + " in " + program.element(u.scope()).qualified())
                .containsExactly("C new in a.C");
        assertThat(projection.needs(program.unresolved().getFirst().id()).orElseThrow().arguments())
                .extracting(a -> a.name() + " " + a.type()).containsExactly("size int", "string String");
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
