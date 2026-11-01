package ide.engine;

import ide.program.Change;
import ide.program.Fact;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Projection;
import ide.projection.kit.MemoryProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static ide.engine.Programs.id;
import static ide.engine.Programs.reference;
import static ide.engine.Programs.remedy;
import static ide.projection.kit.MemoryProjection.PRIVATE_TO_TOP_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;

/** A transaction knows only what each change declares: it expects that, reports anything else, keeps the pins. */
class TransactionTest {

    /** package a: class A; class B extends A { private int x; int y; int n() reads x } */
    private static MemoryProjection program() {
        return MemoryProjection.builder().rules(PRIVATE_TO_TOP_LEVEL)
                .type("a.A")
                .type("a.B").extend("a.B", "a.A")
                .field("a.B", "x", "int").fact("a.B#x", Fact.VISIBILITY, "private")
                .field("a.B", "y", "int")
                .method("a.B", "n", "int").use("a.B#n()", "a.B#x", Mode.READ)
                .build();
    }

    @Test
    void whatAChangeDeclaresIsExpectedNotReported() {
        var projection = program();
        var p = projection.program();
        var tx = new Transaction(projection, new Repairer(List.of()));

        tx.apply(new Primitive.Retarget(reference(p, "a.B#n()", "a.B#x"), id(p, "a.B#y")));
        var result = tx.commit();

        assertThat(result.applied()).isTrue();
        assertThat(result.steps()).singleElement().asString().startsWith("' retarget ").endsWith(" x -> a.B#y");
        assertThat(projection.program().referencesFrom(id(p, "a.B#n()"))).extracting(Reference::target)
                .containsExactly(id(p, "a.B#y"));
    }

    @Test
    void whatAChangeDoesNotDeclareIsReportedAndTheStartRestored() {
        var projection = program();
        var p = projection.program();
        var tx = new Transaction(silent(projection), new Repairer(List.of()));

        tx.apply(new Primitive.Retarget(reference(p, "a.B#n()", "a.B#x"), id(p, "a.B#y")));
        var result = tx.commit();

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("binding.unresolved");
        assertThat(projection.program().referencesFrom(id(p, "a.B#n()"))).extracting(Reference::target)
                .containsExactly(id(p, "a.B#x"));
    }

    @Test
    void aRepairMayNotUndoWhatTheChangePinned() {
        var projection = program();
        var p = projection.program();
        var x = id(p, "a.B#x");
        var moveBack = remedy("move back", violation -> new Primitive.Move(violation.target(), id(p, "a.B"), null));
        var tx = new Transaction(projection, new Repairer(List.of(moveBack, Programs.open())));

        tx.apply(new Primitive.Move(x, id(p, "a.A"), null)); // B#n() now reads a private field of A
        var result = tx.commit();

        assertThat(result.applied()).isTrue();
        assertThat(result.repairs()).containsExactly("open a.A#x");
        assertThat(result.search().lines()).contains("  X move back a.A#x: would undo what the refactoring did");
        assertThat(projection.program().element(x).parent()).isEqualTo(id(p, "a.A"));
    }

    @Test
    void anAttemptThatIsRefusedOrDoesNotVerifyIsUndoneAndTheRestGoesOn() {
        var projection = program();
        var start = projection.program();
        var y = id(start, "a.B#y");
        var tx = new Transaction(projection, new Repairer(List.of()));

        tx.apply(new Primitive.Rename(y, "z"));
        var refused = tx.attempt("rename onto x", () -> tx.apply(new Primitive.Rename(y, "x")));
        var unverified = tx.attempt("move x up", () -> tx.apply(new Primitive.Move(id(start, "a.B#x"), id(start, "a.A"), null)));
        var kept = tx.attempt("add w", () -> tx.apply(new Primitive.Create(ide.program.Kind.FIELD, id(start, "a.A"), "w")));

        assertThat(List.of(refused, unverified, kept)).containsExactly(false, false, true);
        assertThat(projection.program().find("a.A#w")).isPresent();
        assertThat(projection.program().find("a.B#x")).isPresent();
        assertThat(tx.commit().steps()).contains(
                ". try rename onto x", "X rename onto x: projection.duplicate a.B#x already exists",
                ". try move x up", "  ' move a.B#x -> a.A",
                "X move x up: access.insufficient a.B#n() cannot access private a.A#x",
                ". try add w", "  ' create FIELD a.A#w");
    }

    @Test
    void abortReturnsToTheStart() {
        var projection = program();
        var start = projection.program();
        var tx = new Transaction(projection, new Repairer(List.of()));

        tx.apply(new Primitive.Rename(id(start, "a.B#y"), "z"));
        tx.apply(new Primitive.Create(ide.program.Kind.FIELD, id(start, "a.A"), "w"));
        tx.abort(null);

        assertThat(projection.program().elements()).containsExactlyElementsOf(start.elements());
    }

    @Test
    void restoringAPointOfTheTransactionForgetsWhatWasDeclaredSince() {
        var projection = program();
        var p = projection.program();
        var tx = new Transaction(projection, new Repairer(List.of()));

        var point = tx.snapshot();
        tx.apply(new Primitive.Retarget(reference(p, "a.B#n()", "a.B#x"), id(p, "a.B#y")));
        tx.restore(point); // as a plan applied to the transaction does when one of its attempts is refused
        var result = tx.commit();

        assertThat(result.applied()).isTrue();
        assertThat(projection.program().referencesFrom(id(p, "a.B#n()"))).extracting(Reference::target)
                .containsExactly(id(p, "a.B#x"));
    }

    /** A driver that applies primitives but declares nothing about them. */
    private static Projection silent(Projection projection) {
        return new Projection() {
            @Override
            public Program program() {
                return projection.program();
            }

            @Override
            public Change apply(Primitive primitive) {
                projection.apply(primitive);
                return Change.NONE;
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
                return projection.diagnostics();
            }

            @Override
            public Optional<CompletionContext> completionContext(String path, String text, int offset) {
                return Optional.empty();
            }

            @Override
            public Optional<ide.projection.Location> locate(ide.program.ElementId element) {
                return Optional.empty();
            }

            @Override
            public Optional<ide.program.ElementId> elementAt(String path, int line, int column) {
                return Optional.empty();
            }

            @Override
            public Optional<ide.program.ElementId> inside(String path, int line, int column) {
                return Optional.empty();
            }

            @Override
            public Optional<ide.projection.Needs> needs(String use) {
                return Optional.empty();
            }
        };
    }
}
