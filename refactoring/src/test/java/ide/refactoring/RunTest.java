package ide.refactoring;

import ide.engine.Repairer;
import ide.engine.Transaction;
import ide.program.ElementId;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Fact;
import ide.program.Mode;
import ide.program.Program;
import ide.projection.LanguageRules;
import ide.projection.PrimitiveRejected;
import ide.projection.kit.MemoryProjection;
import ide.refactoring.script.Script;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a refactoring sees while it runs. Straight on a projection it needs no engine: that is how a script is tested.
 * Inside a transaction its calls nest in the trace, its attempts are judged, and what it assumes is an obligation.
 */
class RunTest {

    /** package a: class A { int x; } class B { int n() reads A#x; void m() writes A#x } */
    private static MemoryProjection program() {
        return MemoryProjection.builder().rules(MemoryProjection.PRIVATE_TO_TOP_LEVEL)
                .type("a.A").field("a.A", "x", "int")
                .type("a.B")
                .method("a.B", "n", "int").use("a.B#n()", "a.A#x", Mode.READ)
                .method("a.B", "m", "void").use("a.B#m()", "a.A#x", Mode.WRITE)
                .build();
    }

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow().id();
    }

    /** What {@code site} uses, as {@code "<address> <mode>"}. */
    private static List<String> uses(Program program, String site) {
        return program.referencesFrom(id(program, site)).stream()
                .map(r -> program.element(r.target()).qualified() + " " + r.mode().label()).toList();
    }

    @Test
    void aScriptRunsStraightOnAProjectionWithNoEngine() {
        var projection = program();

        var field = Run.on(projection, Library.standard()).call("encapsulate-field", Map.of("field", "a.A#x"));

        var p = projection.program();
        assertThat(p.element(field).fact(Fact.VISIBILITY)).isEqualTo("private");
        assertThat(uses(p, "a.B#n()")).containsExactly("a.A#getX() call");
        assertThat(uses(p, "a.B#m()")).containsExactly("a.A#setX(int) call");
    }

    @Test
    void insideATransactionTheCallsNestInTheTrace() {
        var projection = program();
        var p = projection.program();
        var read = p.referencesFrom(id(p, "a.B#n()")).getFirst().id();
        var write = p.referencesFrom(id(p, "a.B#m()")).getFirst().id();
        var tx = new Transaction(projection, new Repairer(List.of()));

        Run.in(tx, Library.standard()).call("encapsulate-field", Map.of("field", "a.A#x"));
        var steps = tx.abort(null).steps(); // an expansion: what would run, with nothing kept

        assertThat(steps).filteredOn(line -> !line.startsWith("    ")).containsExactly(
                ". encapsulate-field",
                "  . generate-getter",
                "  . generate-setter",
                "  ' retarget " + read + " x -> a.A#getX()",
                "  ' retarget " + write + " x -> a.A#setX(int)",
                "  ' set a.A#x visibility = private");
        assertThat(steps).contains("    ' create METHOD a.A#getX()", "    ' create METHOD a.A#setX()");
        assertThat(projection.program().elements()).containsExactlyElementsOf(p.elements());
    }

    private static final String MOVE_FIELDS = """
            refactoring move-fields "Moves the fields of a type to another, those that can be"
              param from : element(CLASS) "where the fields are"
              param to : element(CLASS) "where they go"
              for f in fields(from)
                try "${name(f)}"
                  move ${f} ${to}
                end
              end
            end
            """;

    /** package a: class A { private int x; int y; int z; int n() reads x } class B { int y; } */
    private static MemoryProjection fields() {
        return MemoryProjection.builder().rules(MemoryProjection.PRIVATE_TO_TOP_LEVEL)
                .type("a.A").field("a.A", "x", "int").fact("a.A#x", Fact.VISIBILITY, "private")
                .field("a.A", "y", "int").field("a.A", "z", "int")
                .method("a.A", "n", "int").use("a.A#n()", "a.A#x", Mode.READ)
                .type("a.B").field("a.B", "y", "int")
                .build();
    }

    @Test
    void straightOnAProjectionAnAttemptIsUndoneOnlyWhenRefused() {
        var projection = fields();
        var library = Library.standard();
        library.define(MOVE_FIELDS);

        Run.on(projection, library).call("move-fields", Map.of("from", "a.A", "to", "a.B"));

        var p = projection.program();
        assertThat(p.find("a.B#x")).isPresent(); // nothing judges it here: A#n() lost access, the repairer would see it
        assertThat(p.find("a.A#y")).isPresent(); // refused: B has a y
        assertThat(p.find("a.B#z")).isPresent();
    }

    @Test
    void insideATransactionAnAttemptIsAlsoUndoneWhenItDoesNotVerify() {
        var projection = fields();
        var library = Library.standard();
        library.define(MOVE_FIELDS);
        var tx = new Transaction(projection, new Repairer(List.of()));

        Run.in(tx, library).call("move-fields", Map.of("from", "a.A", "to", "a.B"));
        var result = tx.commit();

        assertThat(result.applied()).isTrue();
        assertThat(result.steps()).containsExactly(
                ". move-fields",
                "  . try x",
                "    ' move a.A#x -> a.B",
                "  X x: access.insufficient a.A#n() cannot access private a.B#x",
                "  . try y",
                "  X y: projection.duplicate a.B#y already exists",
                "  . try z",
                "    ' move a.A#z -> a.B");
        var p = projection.program();
        assertThat(p.find("a.A#x")).isPresent();
        assertThat(p.find("a.A#y")).isPresent();
        assertThat(p.find("a.B#z")).isPresent();
    }

    private static final String HIDE = """
            refactoring hide "Makes a field private, assuming nothing outside the workspace uses it"
              param field : element(FIELD) "the field"
              oblige "nothing outside the workspace uses ${qualified(field)}"
              set ${field} visibility private
            end
            """;

    /** package a: class A { int x; } */
    private static MemoryProjection lone() {
        return MemoryProjection.builder().type("a.A").field("a.A", "x", "int").build();
    }

    /** A script knows no language: what only a language knows, such as how it writes a value, it asks the rules. */
    @Test
    void whatOnlyALanguageKnowsAScriptAsksItsRules() {
        var rules = new LanguageRules() {
            @Override
            public boolean accessible(Program program, ElementId site, ElementId target) {
                return true;
            }

            @Override
            public List<String> visibilities() {
                return List.of("hidden", "shown");
            }

            @Override
            public String defaultValue(String type) {
                return type.equals("int") ? "zero" : "nothing";
            }
        };
        var projection = MemoryProjection.builder().rules(rules).type("a.A").field("a.A", "x", "int").build();
        var field = new Value.Elem(id(projection.program(), "a.A#x"));

        var text = Script.render("${default-value(type(field))}", Run.on(projection, Library.standard()), Map.of("field", field));

        assertThat(text).isEqualTo("zero");
    }

    @Test
    void anAssumptionIsAnObligationInsideATransactionAndRefusedStraightOnAProjection() {
        var library = Library.standard();
        library.define(HIDE);

        var tx = new Transaction(lone(), new Repairer(List.of()));
        Run.in(tx, library).call("hide", Map.of("field", "a.A#x"));
        var result = tx.commit();
        assertThat(result.guarantee()).isEqualTo("assumed");
        assertThat(result.obligations()).containsExactly("nothing outside the workspace uses a.A#x");

        // straight on a projection is how the repairer runs a plan: it cannot assume what it cannot check
        assertThatThrownBy(() -> Run.on(lone(), library).call("hide", Map.of("field", "a.A#x")))
                .isInstanceOfSatisfying(PrimitiveRejected.class, refused -> assertThat(refused.code()).isEqualTo("plan.assumes"));
    }
}
