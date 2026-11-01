package ide.engine;

import ide.projection.Diagnostic;
import ide.projection.Projection;
import ide.program.Change;
import ide.program.Fact;
import ide.program.Program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifies and, while violations remain, takes the first one and tries its plans cheapest first from a saved
 * point: a plan is kept if that violation is gone, else undone. Stops at a fixpoint, when no plan works, or at
 * the caps ({@value #MAX_ROUNDS} rounds, {@value #MAX_BRANCHES} plans tried).
 */
public final class Repairer {

    static final int MAX_ROUNDS = 10;
    static final int MAX_BRANCHES = 16;

    /**
     * Repairs applied, the violations left (empty if the transaction can close), and the search: each violation a
     * question, each plan kept an answer, each plan undone dropped with its reason.
     */
    /** What the repairs did, what is left, how they were searched, and the program meant once they are folded in. */
    public record Outcome(List<String> repairs, List<Violation> violations, Trace search, Program baseline) {
    }

    private final Verifier verifier;
    private final List<Remedy> remedies;

    public Repairer(Verifier verifier, List<Remedy> remedies) {
        this.verifier = verifier;
        this.remedies = List.copyOf(remedies);
    }

    /** With the standard views. */
    public Repairer(List<Remedy> remedies) {
        this(Verifier.standard(), remedies);
    }

    /** @param errorsBefore the compiler's errors before the transaction: the only ones it may leave */
    public Outcome stabilize(Program baseline, List<Diagnostic> errorsBefore, Projection projection) {
        return stabilize(baseline, errorsBefore, projection, List.of());
    }

    /**
     * @param pinned what the transaction changed on purpose. A plan that changes a pinned aspect would undo the
     *               refactoring's intent, so it is undone instead.
     */
    public Outcome stabilize(Program baseline, List<Diagnostic> errorsBefore, Projection projection, Collection<Change.Pin> pinned) {
        var repairs = new ArrayList<String>();
        var search = new Trace();
        var branches = 0;
        var meant = baseline; // what the repairs kept mean is folded in, as a change's is
        for (var round = 0; round < MAX_ROUNDS; round++) {
            var violations = verify(meant, errorsBefore, projection);
            if (violations.isEmpty()) return new Outcome(repairs, List.of(), search, meant);
            String kept = null;
            for (var target : violations) { // one with no plan may follow from another: the next one is tried
                search.open(Trace.Kind.QUESTION, target.code() + " " + target.detail());
                var plans = remedies.stream().flatMap(r -> r.plans(target, projection).stream())
                        .filter(plan -> plan.asks().isEmpty() && !plan.changesMeaning()).toList(); // the user's to choose, never guessed
                if (plans.isEmpty()) search.add(Trace.Kind.NOTE, "(no plan)");
                for (var plan : plans) {
                    if (branches++ >= MAX_BRANCHES) {
                        search.add(Trace.Kind.DROPPED, "branch cap reached").close();
                        return new Outcome(repairs, violations, search, meant);
                    }
                    var saved = projection.snapshot();
                    var intent = state(projection.program(), pinned);
                    var folding = Transaction.folding(projection, meant);
                    try {
                        plan.apply(folding);
                    } catch (RuntimeException failed) {
                        projection.restore(saved);
                        search.add(Trace.Kind.DROPPED, plan.title() + ": " + String.valueOf(failed.getMessage()).lines().findFirst().orElse(""));
                        continue;
                    }
                    if (!state(projection.program(), pinned).equals(intent)) {
                        projection.restore(saved);
                        search.add(Trace.Kind.DROPPED, plan.title() + ": would undo what the refactoring did");
                        continue;
                    }
                    if (!same(target, verify(folding.baseline(), errorsBefore, projection))) {
                        kept = plan.title();
                        meant = folding.baseline();
                        search.add(Trace.Kind.ANSWER, plan.title());
                        break;
                    }
                    projection.restore(saved);
                    search.add(Trace.Kind.DROPPED, plan.title() + ": still " + target.code());
                }
                search.close();
                if (kept != null) break;
            }
            if (kept == null) return new Outcome(repairs, violations, search, meant);
            repairs.add(kept);
        }
        return new Outcome(repairs, verify(meant, errorsBefore, projection), search, meant);
    }

    /** The current value of every pinned aspect. */
    private static Map<Change.Pin, String> state(Program program, Collection<Change.Pin> pinned) {
        var state = new HashMap<Change.Pin, String>();
        for (var pin : pinned) {
            if (!program.contains(pin.element())) continue;
            var e = program.element(pin.element());
            state.put(pin, switch (pin.aspect()) {
                case PARENT -> String.valueOf(e.parent());
                case NAME -> e.name();
                default -> String.valueOf(e.fact(pin.aspect().fact()));
            });
        }
        return state;
    }

    private List<Violation> verify(Program baseline, List<Diagnostic> errorsBefore, Projection projection) {
        return verifier.verify(new Comparison(baseline, projection, errorsBefore));
    }

    /** Whether the violation is still among {@code violations} (same code, site and target). */
    private static boolean same(Violation violation, List<Violation> violations) {
        return violations.stream().anyMatch(v -> v.code().equals(violation.code())
                && java.util.Objects.equals(v.site(), violation.site()) && java.util.Objects.equals(v.target(), violation.target())
                && v.facts().equals(violation.facts()));
    }
}
