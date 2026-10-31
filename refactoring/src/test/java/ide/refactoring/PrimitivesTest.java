package ide.refactoring;

import ide.core.Param;
import ide.program.Fact;
import ide.projection.kit.MemoryProjection;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** The primitives of the vocabulary are refactorings, read off their records: nothing is registered by hand. */
class PrimitivesTest {

    private static Refactoring primitive(String id) {
        return Primitives.all().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyPrimitiveIsARefactoringWhoseParametersAreItsParts() {
        assertThat(Primitives.all()).extracting(Refactoring::id, Refactoring::level).containsExactlyInAnyOrder(
                tuple("create", "primitive"), tuple("delete", "primitive"), tuple("move", "primitive"),
                tuple("rename", "primitive"), tuple("set", "primitive"), tuple("link", "primitive"),
                tuple("retarget", "primitive"), tuple("extract", "primitive"), tuple("inline", "primitive"));
        assertThat(primitive("move").params()).extracting(Param::name, Param::type, Param::required).containsExactly(
                tuple("element", "element", true), tuple("parent", "element", true), tuple("before", "element", false));
        assertThat(primitive("link").params().get(1).kinds()).contains("EXTENDS", "IMPLEMENTS", "DECLARED");
        assertThat(primitive("rename").description()).isEqualTo("Renames an element and every use of it");
    }

    @Test
    void aPrimitiveAppliesToTheProjectionAndAnswersTheElement() {
        var projection = MemoryProjection.builder().type("a.A").type("a.B").field("a.A", "x", "int").build();
        var run = Run.on(projection, Library.standard());

        var created = primitive("create").apply(run, new Args(Map.of("kind", "METHOD", "parent", "a.B", "name", "get",
                "facts", "type=int; visibility=public")));
        var moved = primitive("move").apply(run, new Args(Map.of("element", "a.A#x", "parent", "a.B")));

        var program = projection.program();
        assertThat(program.element(created).qualified()).isEqualTo("a.B#get()");
        assertThat(program.element(created).fact(Fact.VISIBILITY)).isEqualTo("public");
        assertThat(program.element(moved).qualified()).isEqualTo("a.B#x");
    }
}
