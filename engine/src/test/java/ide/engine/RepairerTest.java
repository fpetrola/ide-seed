package ide.engine;

import ide.program.Fact;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;
import ide.projection.kit.MemoryProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static ide.engine.Programs.id;
import static ide.engine.Programs.open;
import static ide.engine.Programs.remedy;
import static ide.projection.kit.MemoryProjection.PRIVATE_TO_TOP_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;

/** The repairer searches remedies' plans from saved points, one violation per round, within its caps. */
class RepairerTest {

    private MemoryProjection projection;
    private Program before;

    /** B#n() reads two private fields of B, and is moved to A: two access violations. */
    @BeforeEach
    void twoInaccessibleReads() {
        projection = MemoryProjection.builder().rules(PRIVATE_TO_TOP_LEVEL)
                .type("a.A").type("a.B")
                .field("a.B", "x", "int").fact("a.B#x", Fact.VISIBILITY, "private")
                .field("a.B", "z", "int").fact("a.B#z", Fact.VISIBILITY, "private")
                .method("a.B", "n", "int").use("a.B#n()", "a.B#x", Mode.READ).use("a.B#n()", "a.B#z", Mode.READ)
                .build();
        before = projection.program();
        projection.apply(new Primitive.Move(id(before, "a.B#n()"), id(before, "a.A"), null));
    }

    @Test
    void repairsOneViolationPerRoundUntilAFixpoint() {
        var outcome = new Repairer(List.of(open())).stabilize(before, List.of(), projection);

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.repairs()).containsExactly("open a.B#x", "open a.B#z");
        assertThat(outcome.search().lines()).startsWith("? access.insufficient a.A#n() cannot access private a.B#x", "  ! open a.B#x");
    }

    @Test
    void withoutAPlanTheViolationsStay() {
        var outcome = new Repairer(List.of()).stabilize(before, List.of(), projection);

        assertThat(outcome.violations()).extracting(Violation::code).containsExactly("access.insufficient", "access.insufficient");
        assertThat(outcome.search().lines()).containsExactly("? access.insufficient a.A#n() cannot access private a.B#x", "  (no plan)",
                "? access.insufficient a.A#n() cannot access private a.B#z", "  (no plan)");
    }

    @Test
    void plansThatDoNotHelpAreUndoneUntilTheBranchCap() {
        Remedy useless = (violation, p) -> IntStream.range(0, 20).mapToObj(i -> new Plan("useless " + i, List.of(),
                (target, answers) -> target.apply(new Primitive.Set(violation.target(), "static", "true")))).toList();

        var outcome = new Repairer(List.of(useless)).stabilize(before, List.of(), projection);

        assertThat(outcome.violations()).isNotEmpty();
        assertThat(outcome.search().lines()).filteredOn(line -> line.startsWith("  X useless")).hasSize(Repairer.MAX_BRANCHES);
        assertThat(outcome.search().lines()).last().isEqualTo("  X branch cap reached");
        assertThat(projection.program().element(id(before, "a.B#x")).fact(Fact.STATIC)).isEqualTo("false");
    }

    @Test
    void aPlanWithQuestionsIsNeverAppliedAutomatically() {
        Remedy asking = (violation, p) -> List.of(new Plan("ask", List.of(new Plan.Ask("v", "which visibility?", "package")),
                (target, answers) -> target.apply(new Primitive.Set(violation.target(), "visibility", answers.get("v")))));

        var outcome = new Repairer(List.of(asking)).stabilize(before, List.of(), projection);

        assertThat(outcome.repairs()).isEmpty();
        assertThat(outcome.search().lines()).contains("  (no plan)");
    }

    @Test
    void aPlanThatFailsIsUndoneAndTheNextIsTried() {
        var failing = remedy("fail", violation -> {
            throw new PrimitiveRejected("test.refused", "refused on purpose");
        });

        var outcome = new Repairer(List.of(failing, open())).stabilize(before, List.of(), projection);

        assertThat(outcome.violations()).isEmpty();
        assertThat(outcome.search().lines()).contains("  X fail a.B#x: refused on purpose");
        assertThat(outcome.repairs()).containsExactly("open a.B#x", "open a.B#z");
    }
}
