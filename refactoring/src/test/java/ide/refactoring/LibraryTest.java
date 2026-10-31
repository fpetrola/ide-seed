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
}
