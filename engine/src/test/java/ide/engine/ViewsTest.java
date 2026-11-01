package ide.engine;

import ide.program.Body;
import ide.program.Change;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Projection;
import ide.projection.kit.MemoryProjection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ide.engine.Programs.id;
import static ide.engine.Programs.reference;
import static ide.projection.kit.MemoryProjection.PRIVATE_TO_TOP_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;

/** Each view alone: what it sees between the program a change meant to leave and the program it left. */
class ViewsTest {

    @Test
    void bindingSeesAUseThatNowBindsSomethingElseOrNothing() {
        var projection = MemoryProjection.builder().rules(PRIVATE_TO_TOP_LEVEL)
                .type("a.A").field("a.A", "x", "int")
                .type("a.B").field("a.B", "x", "int")
                .type("a.C").field("a.C", "a", "a.A").method("a.C", "m", "int").use("a.C#m()", "a.A#x", Mode.READ)
                .build();
        var before = projection.program();

        projection.apply(new Primitive.Retarget(reference(before, "a.C#m()", "a.A#x"), id(before, "a.B#x")));
        projection.apply(new Primitive.Set(id(before, "a.C#a"), "type", "int"));

        assertThat(new BindingView().check(new Comparison(before, projection, true)))
                .extracting(Violation::code, Violation::detail).containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("binding.rebound", "a.C#m() now binds x to a.B#x instead of a.A#x"),
                        org.assertj.core.groups.Tuple.tuple("binding.unresolved", "a.C#a no longer binds to a.A"));
    }

    @Test
    void bindingSeesABodyThatBindsSomethingElseThanItNames() {
        var projection = MemoryProjection.builder()
                .type("a.A").field("a.A", "x", "int")
                .type("a.B").field("a.B", "x", "int").method("a.B", "m", "int").method("a.B", "n", "int")
                .build();
        var before = projection.program();
        var m = id(before, "a.B#m()");
        var n = id(before, "a.B#n()");
        var y = projection.apply(new Primitive.Create(Kind.FIELD, id(before, "a.B"), "y")).produced();
        // the bodies named A's x and the new y; what was written binds B's x, and nothing
        var meant = before.expecting(new Change.Added(m, id(before, "a.A#x"), "x", Mode.READ))
                .expecting(new Change.Added(n, y, "y", Mode.READ));
        projection.apply(new Primitive.Set(m, "body", "return " + Body.name(id(before, "a.B#x"))));

        assertThat(new BindingView().check(new Comparison(meant, projection, true)))
                .extracting(Violation::code, Violation::detail).containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("binding.rebound", "a.B#m() now binds x to a.B#x instead of a.A#x"),
                        org.assertj.core.groups.Tuple.tuple("binding.unresolved", "a.B#n() no longer binds to a.B#y"));
    }

    @Test
    void accessSeesWhatCannotBeReachedNowButNotWhatCouldNotBefore() {
        var projection = MemoryProjection.builder().rules(PRIVATE_TO_TOP_LEVEL)
                .type("a.A").field("a.A", "x", "int").field("a.A", "y", "int").fact("a.A#y", Fact.VISIBILITY, "private")
                .type("a.B").method("a.B", "m", "int").use("a.B#m()", "a.A#x", Mode.READ).use("a.B#m()", "a.A#y", Mode.READ)
                .build();
        var before = projection.program();

        projection.apply(new Primitive.Set(id(before, "a.A#x"), "visibility", "private"));

        assertThat(new AccessView().check(new Comparison(before, projection, true)))
                .extracting(Violation::code, Violation::detail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("access.insufficient", "a.B#m() cannot access private a.A#x"));
    }

    @Test
    void dispatchSeesOverridesGainedAndLost() {
        var projection = MemoryProjection.builder().rules(PRIVATE_TO_TOP_LEVEL)
                .type("a.A").method("a.A", "m", "void")
                .type("a.B").extend("a.B", "a.A").method("a.B", "m", "void")
                .type("a.C").extend("a.C", "a.A")
                .type("a.D").method("a.D", "m", "void")
                .build();
        var before = projection.program();

        projection.apply(new Primitive.Rename(id(before, "a.B#m()"), "n"));
        projection.apply(new Primitive.Move(id(before, "a.D#m()"), id(before, "a.C"), null));

        assertThat(new DispatchView().check(new Comparison(before, projection, true))).extracting(Violation::detail)
                .containsExactlyInAnyOrder("a.C#m() would override a.A#m()", "a.B#m() no longer overrides a.A#m()");
    }

    @Test
    void theCompilerSeesItsErrorsOnlyIfTheCodeCompiledBefore() {
        var memory = MemoryProjection.builder().type("a.A").build();
        var projection = reporting(memory, new Diagnostic("javac.cant.resolve", "a/A.java", 3, "List", "cannot find symbol"));
        var view = new CompilerView();

        assertThat(view.check(new Comparison(memory.program(), projection, true))).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo("javac.cant.resolve");
            assertThat(v.facts()).isEqualTo(Map.of("symbol", "List", "path", "a/A.java", "line", "3"));
        });
        assertThat(view.check(new Comparison(memory.program(), projection, false))).isEmpty();
        assertThat(view.costly()).isTrue();
    }

    @Test
    void costlyViewsRunOnlyWhenTheCheapOnesSeeNothing() {
        var projection = MemoryProjection.builder().type("a.A").build();
        var comparison = new Comparison(projection.program(), projection, true);
        var asked = new ArrayList<String>();
        View costly = new View() {
            @Override
            public List<Violation> check(Comparison c) {
                asked.add("costly");
                return List.of(new Violation("costly.seen", null, null, "seen"));
            }

            @Override
            public boolean costly() {
                return true;
            }
        };
        View seeing = c -> List.of(new Violation("cheap.seen", null, null, "seen"));
        View blind = c -> List.of();

        assertThat(new Verifier(List.of(costly, seeing)).verify(comparison)).extracting(Violation::code).containsExactly("cheap.seen");
        assertThat(asked).isEmpty();
        assertThat(new Verifier(List.of(costly, blind)).verify(comparison)).extracting(Violation::code).containsExactly("costly.seen");
        assertThat(asked).containsExactly("costly");
    }

    /** A projection that reports compiler errors. */
    private static Projection reporting(Projection projection, Diagnostic... diagnostics) {
        return new Projection() {
            @Override
            public Program program() {
                return projection.program();
            }

            @Override
            public Change apply(Primitive primitive) {
                return projection.apply(primitive);
            }

            @Override
            public void edit(String path, String text) {
                projection.edit(path, text);
            }

            @Override
            public Snapshot snapshot() {
                return projection.snapshot();
            }

            @Override
            public void restore(Snapshot snapshot) {
                projection.restore(snapshot);
            }

            @Override
            public LanguageRules rules() {
                return projection.rules();
            }

            @Override
            public List<Diagnostic> diagnostics() {
                return List.of(diagnostics);
            }

            @Override
            public Optional<CompletionContext> completionContext(String path, String text, int offset) {
                return Optional.empty();
            }
        };
    }
}
