package ide.session;

import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Program;
import ide.projection.CompletionContext;
import ide.projection.kit.MemoryProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Completion knows no language: given what the projection read at a point, what may be written there. */
class CompletionTest {

    /** class A { private int secret; int count; static int max; void reset() } B extends A { int bonus; int total() } C */
    private static final MemoryProjection PROJECTION = MemoryProjection.builder()
            .rules(MemoryProjection.PRIVATE_TO_TOP_LEVEL)
            .type("a.A").field("a.A", "secret", "int").fact("a.A#secret", Fact.VISIBILITY, "private")
            .field("a.A", "count", "int").field("a.A", "max", "int").fact("a.A#max", Fact.STATIC, "true")
            .method("a.A", "reset", "void")
            .type("a.B").extend("a.B", "a.A").field("a.B", "bonus", "int").method("a.B", "total", "int")
            .type("a.C").method("a.C", "use", "void")
            .build();

    private static ElementId id(String address) {
        Program program = PROJECTION.program();
        return program.find(address).orElseThrow().id();
    }

    private static List<String> at(String prefix, boolean staticOnly, String... locals) {
        var context = new CompletionContext(id("a.B"), prefix, id("a.C#use()"), staticOnly, List.of(locals));
        return new Completion(PROJECTION).candidates(context);
    }

    @Test
    void localsFirstThenTheAccessibleMembersOfTheReceiverNearestFirst() {
        assertThat(at("", false, "b")).containsExactly("b", "bonus", "total()", "count", "max", "reset()");
    }

    @Test
    void aPrefixNarrowsAndATypeNameOffersOnlyStaticMembers() {
        assertThat(at("to", false)).containsExactly("total()");
        assertThat(at("", true)).containsExactly("max");
    }
}
