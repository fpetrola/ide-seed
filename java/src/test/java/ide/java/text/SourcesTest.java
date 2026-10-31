package ide.java.text;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import ide.projection.PrimitiveRejected;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The text layer as a product: source files change only by text, each re-parsed on its own, all or nothing. */
class SourcesTest {

    private static final String A = """
            package a;

            class A {
                int x;
                int y;
            }
            """;
    private static final String B = """
            package a;

            class B {
            }
            """;

    private final ArrayList<String> changes = new ArrayList<>();
    private final Sources sources = new Sources(new ParserConfiguration(), () -> changes.add("changed"));

    private static TypeDeclaration<?> type(Unit unit) {
        return unit.tree().getType(0);
    }

    private static FieldDeclaration field(Unit unit, int index) {
        return type(unit).getFields().get(index);
    }

    @Test
    void editsApplyBackToFrontOnTheTextTheyWereMadeOn() {
        sources.put(Map.of("a/A.java", A));
        var a = sources.unit("a/A.java");

        sources.apply(new EditSet()
                .replace(a, field(a, 0).getVariable(0).getName(), "first")
                .replace(a, field(a, 1).getVariable(0).getName(), "second")
                .insertBefore(a, field(a, 0), "/* f */ "));

        assertThat(sources.unit("a/A.java").text()).isEqualTo("""
                package a;

                class A {
                    /* f */ int first;
                    int second;
                }
                """);
    }

    @Test
    void insertionsAtOnePlaceKeepTheOrderTheyWereMadeIn() {
        sources.put(Map.of("a/A.java", A));
        var a = sources.unit("a/A.java");

        sources.apply(new EditSet().insertAfter(a, field(a, 1), " // one").insertAfter(a, field(a, 1), " // two"));

        assertThat(sources.unit("a/A.java").text()).contains("int y; // one // two");
    }

    @Test
    void onlyTheUnitsTouchedAreParsedAgainAndEachChangeIsAnnouncedOnce() {
        sources.put(Map.of("a/A.java", A, "a/B.java", B));
        var a = sources.unit("a/A.java");
        var b = sources.unit("a/B.java");
        changes.clear();

        sources.apply(new EditSet().replace(a, type(a).getName(), "C"));

        assertThat(sources.unit("a/B.java")).isSameAs(b);
        assertThat(sources.unit("a/A.java").tree()).isNotSameAs(a.tree());
        assertThat(sources.unitOf(sources.unit("a/A.java").tree().getType(0)).path()).isEqualTo("a/A.java");
        assertThat(changes).containsExactly("changed");
    }

    @Test
    void anEditThatLeavesAnyUnitUnparsableIsRefusedAndChangesNothing() {
        sources.put(Map.of("a/A.java", A, "a/B.java", B));
        var a = sources.unit("a/A.java");
        var b = sources.unit("a/B.java");
        changes.clear();

        var edits = new EditSet().replace(a, type(a).getName(), "C").replace(b, type(b).getName(), "class");

        assertThatThrownBy(() -> sources.apply(edits)).isInstanceOfSatisfying(PrimitiveRejected.class,
                rejected -> assertThat(rejected.code()).isEqualTo("projection.unparsable"));
        assertThat(sources.unit("a/A.java")).isSameAs(a);
        assertThat(sources.unit("a/B.java")).isSameAs(b);
        assertThat(changes).isEmpty();
    }

    @Test
    void removingLinesTakesTheBlankLineTheyWouldLeaveDoubledOrAgainstABrace() {
        sources.put(Map.of("a/C.java", """
                package a;

                class C {

                    int x;

                    int y;
                }
                """));
        var c = sources.unit("a/C.java");

        sources.apply(new EditSet().removeLines(c, 5, 5)); // x: blank before and after
        assertThat(sources.unit("a/C.java").text()).isEqualTo("""
                package a;

                class C {

                    int y;
                }
                """);

        var again = sources.unit("a/C.java");
        sources.apply(new EditSet().removeLines(again, 5, 5)); // y: blank before, brace after
        assertThat(sources.unit("a/C.java").text()).isEqualTo("""
                package a;

                class C {
                }
                """);
    }

    @Test
    void filesAreAddedRenamedAndRemovedWholeAndRestoredAtOnce() {
        sources.put(Map.of("a/A.java", A));
        var saved = sources.texts();

        sources.change(Map.of("a/B.java", B), java.util.List.of("a/A.java"));
        sources.rename("a/B.java", "b/B.java");
        assertThat(sources.paths()).containsExactly("b/B.java");

        sources.restore(saved);
        assertThat(sources.texts()).isEqualTo(Map.of("a/A.java", A));
    }

    @Test
    void positionsAreFoundInTheText() {
        sources.put(Map.of("a/A.java", A));
        var a = sources.unit("a/A.java");

        assertThat(a.line(4)).isEqualTo("    int x;");
        assertThat(a.textOf(field(a, 1))).isEqualTo("int y;");
        assertThat(a.next(';', field(a, 0).getBegin().orElseThrow()).line).isEqualTo(4);
        assertThat(a.indentation(4)).isEqualTo("    ");
    }
}
