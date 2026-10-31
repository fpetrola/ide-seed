package ide.engine;

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
    public record Outcome(List<String> repairs, List<Violation> violations, Trace search) {
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

    /** @param compiled whether the program compiled before the transaction; only then is it held to compile after */
    public Outcome stabilize(Program baseline, boolean compiled, Projection projection) {
        return stabilize(baseline, compiled, projection, List.of());
    }

    /**
     * @param pinned what the transaction changed on purpose. A plan that changes a pinned aspect would undo the
     *               refactoring's intent, so it is undone instead.
     */
    public Outcome stabilize(Program baseline, boolean compiled, Projection projection, Collection<Change.Pin> pinned) {
        var repairs = new ArrayList<String>();
        var search = new Trace();
        var branches = 0;
        for (var round = 0; round < MAX_ROUNDS; round++) {
            var violations = verify(baseline, compiled, projection);
            if (violations.isEmpty()) return new Outcome(repairs, List.of(), search);
            var target = violations.getFirst();
            search.open(Trace.Kind.QUESTION, target.code() + " " + target.detail());
            var plans = remedies.stream().flatMap(r -> r.plans(target, projection, projection.rules()).stream())
                    .filter(plan -> plan.asks().isEmpty()).toList(); // holes are for the user to fill, never guessed
            String kept = null;
            if (plans.isEmpty()) search.add(Trace.Kind.NOTE, "(no plan)");
            for (var plan : plans) {
                if (branches++ >= MAX_BRANCHES) {
                    search.add(Trace.Kind.DROPPED, "branch cap reached").close();
                    return new Outcome(repairs, violations, search);
                }
                var saved = projection.snapshot();
                var intent = state(projection.program(), pinned);
                try {
                    plan.apply(projection);
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
                if (!same(target, verify(baseline, compiled, projection))) {
                    kept = plan.title();
                    search.add(Trace.Kind.ANSWER, plan.title());
                    break;
                }
                projection.restore(saved);
                search.add(Trace.Kind.DROPPED, plan.title() + ": still " + target.code());
            }
            search.close();
            if (kept == null) return new Outcome(repairs, violations, search);
            repairs.add(kept);
        }
        return new Outcome(repairs, verify(baseline, compiled, projection), search);
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
                case VISIBILITY -> String.valueOf(e.fact(Fact.VISIBILITY));
            });
        }
        return state;
    }

    private List<Violation> verify(Program baseline, boolean compiled, Projection projection) {
        return verifier.verify(new Comparison(baseline, projection, compiled));
    }

    /** Whether the violation is still among {@code violations} (same code, site and target). */
    private static boolean same(Violation violation, List<Violation> violations) {
        return violations.stream().anyMatch(v -> v.code().equals(violation.code())
                && java.util.Objects.equals(v.site(), violation.site()) && java.util.Objects.equals(v.target(), violation.target())
                && v.facts().equals(violation.facts()));
    }
}
