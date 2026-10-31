package ide.session;

import ide.program.Kind;
import ide.program.Mode;
import ide.projection.kit.MemoryProjection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Navigation knows no language: who calls, where a type sits, who uses, over a projection with none. */
class NavigationTest {

    /** interface Shape { area() }; Square implements Shape; Tile extends Square; Report and Main call area(). */
    private static final Navigation NAVIGATION = new Navigation(MemoryProjection.builder()
            .type(Kind.INTERFACE, "a.Shape").method("a.Shape", "area", "double")
            .type("a.Square").implement("a.Square", "a.Shape").method("a.Square", "area", "double")
            .type("a.Tile").extend("a.Tile", "a.Square")
            .type("a.Report").method("a.Report", "total", "double").use("a.Report#total()", "a.Shape#area()", Mode.CALL)
            .type("a.Main").method("a.Main", "run", "void").use("a.Main#run()", "a.Square#area()", Mode.CALL)
            .build());

    @Test
    void theCallersOfAMethodIncludeTheCallsThatMayDispatchToIt() {
        assertThat(NAVIGATION.callers("a.Square#area()")).containsExactly("a.Main#run()", "a.Report#total()");
        assertThat(NAVIGATION.callers("a.Shape#area()")).containsExactly("a.Report#total()");
    }

    @Test
    void aTypeSitsBetweenItsSupertypesAndItsSubtypes() {
        assertThat(NAVIGATION.hierarchy("a.Square")).containsExactly("a.Shape", "> a.Square", "  a.Tile");
    }

    @Test
    void theReferencesOfAnElementAreTheSitesThatUseIt() {
        assertThat(NAVIGATION.references("a.Shape#area()")).containsExactly("a.Report#total()");
        assertThat(NAVIGATION.model()).contains("INTERFACE a.Shape", "a.Main#run() -> a.Square#area() (call)");
    }
}
