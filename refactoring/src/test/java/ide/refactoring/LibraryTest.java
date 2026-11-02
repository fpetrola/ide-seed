package ide.refactoring;

import ide.core.Param;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The library as a product: refactorings called by id, the standard ones and those defined in .ref text. */
class LibraryTest {

    @Test
    void theStandardLibraryHasThePrimitivesTheJavaCompositeAndTheCatalog() {
        var library = Library.standard();

        assertThat(library.listing()).contains("primitive rename", "primitive link", "composite change-signature",
                "script encapsulate-field", "script extract-superclass", "intent pull-up-field");
        assertThat(library.get("rename").params()).extracting(Param::name).containsExactly("element", "name");
    }

    @Test
    void definedScriptsAreCalledByTheirIdAndUnknownIdsAreRefused() {
        var library = Library.standard();

        var ids = library.define("""
                refactoring make-private "Makes a member private"
                  param member : element(FIELD,METHOD) "the member"
                  set ${member} visibility private
                end
                """);

        assertThat(ids).containsExactly("make-private");
        assertThat(library.get("make-private").level()).isEqualTo("script");
        assertThatThrownBy(() -> library.get("nonexistent")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown refactoring: nonexistent");
    }

    private static final String HIDE = """
            intent hide "Makes a member private"
              param member : element(FIELD,METHOD) "the member"
              want visibility(member, "private")
                and given kind(member, k)
            end
            """;

    /**
     * An intent says what is to hold at the end, in the planner's language, and nothing of how: the library's planner
     * deduces it and makes it, with the parameters as values.
     */
    @Test
    void anIntentIsMadeToHoldByTheLibrarysPlanner() {
        var library = Library.standard();
        var asked = new java.util.ArrayList<String>();
        library.planner((intent, want, variables, run) -> asked.add(intent + ": " + want + " with " + variables));
        var projection = ide.projection.kit.MemoryProjection.builder().type("a.A").field("a.A", "x", "int").build();
        var x = projection.program().find("a.A#x").orElseThrow().id();

        library.define(HIDE);
        Run.on(projection, library).call("hide", java.util.Map.of("member", "a.A#x"));

        assertThat(library.get("hide").level()).isEqualTo("intent");
        assertThat(asked).containsExactly("hide: visibility(member, \"private\") and given kind(member, k) with {member=" + new Value.Elem(x) + "}");
    }

    @Test
    void withoutAPlannerAnIntentIsRefused() {
        var library = new Library();
        library.define(HIDE);
        var projection = ide.projection.kit.MemoryProjection.builder().type("a.A").field("a.A", "x", "int").build();

        assertThatThrownBy(() -> Run.on(projection, library).call("hide", java.util.Map.of("member", "a.A#x")))
                .isInstanceOfSatisfying(ide.projection.PrimitiveRejected.class, refused -> assertThat(refused.code()).isEqualTo("intent.unplanned"));
    }

    @Test
    void aRefactoringSuggestsWhatIsNotGivenFromWhatIs() {
        var library = Library.standard();
        var projection = ide.projection.kit.MemoryProjection.builder()
                .type("a.Money").method("a.Money", "add", "Money", "long cents", "int times")
                .build();
        var run = Run.on(projection, library);
        var p = projection.program();

        assertThat(library.get("extract-superclass").suggestions(run, new Args(java.util.Map.of("type", p.find("a.Money").orElseThrow().id()))))
                .containsExactly(java.util.Map.entry("name", "AbstractMoney"));
        assertThat(library.get("change-signature").suggestions(run, new Args(java.util.Map.of("method", "a.Money#add(long,int)"))))
                .containsExactly(java.util.Map.entry("parameters", "long cents, int times"));
        assertThat(library.get("extract-superclass").suggestions(run, new Args(java.util.Map.of()))).isEmpty();
    }

    @Test
    void aRefactoringNarrowsWhatAParameterTakesFromWhatIsGiven() {
        var library = Library.standard();
        var projection = ide.projection.kit.MemoryProjection.builder()
                .type("a.Money").field("a.Money", "cents", "long").field("a.Money", "currency", "String").method("a.Money", "add", "Money")
                .type("a.Other").field("a.Other", "unrelated", "int")
                .build();
        var run = Run.on(projection, library);
        var money = projection.program().find("a.Money").orElseThrow().id();

        assertThat(library.get("extract-superclass").options(run, new Args(java.util.Map.of("type", money)), "fields"))
                .contains(java.util.List.of("a.Money#cents", "a.Money#currency"));
        assertThat(library.get("extract-superclass").options(run, new Args(java.util.Map.of()), "fields")).isEmpty();
        assertThat(library.get("extract-superclass").options(run, new Args(java.util.Map.of("type", money)), "type")).isEmpty();
    }
}
