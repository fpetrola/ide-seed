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
                "script encapsulate-field", "script extract-superclass");
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
}
