package ide.coherence;

import ide.graph.LanguageRules;
import ide.graph.Program;
import ide.graph.ProgramGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies and, while violations remain, takes the first one and tries its plans cheapest first from a saved
 * point: a plan is kept if that violation is gone, else undone. Stops at a fixpoint, when no plan works, or at
 * the caps ({@value #MAX_ROUNDS} rounds, {@value #MAX_BRANCHES} plans tried).
 */
public final class Repairer {

    static final int MAX_ROUNDS = 10;
    static final int MAX_BRANCHES = 16;

    /** Repairs applied, plus the violations left (empty if the transaction can close). */
    public record Outcome(List<String> repairs, List<Violation> violations) {
    }

    private final Verifier verifier;
    private final LanguageRules rules;
    private final List<Remedy> remedies;

    public Repairer(LanguageRules rules, List<Remedy> remedies) {
        this.verifier = new Verifier(rules);
        this.rules = rules;
        this.remedies = List.copyOf(remedies);
    }

    public Outcome stabilize(ProgramGraph baseline, Program program) {
        var repairs = new ArrayList<String>();
        var branches = 0;
        for (var round = 0; round < MAX_ROUNDS; round++) {
            var violations = verifier.verify(baseline, program.graph());
            if (violations.isEmpty()) return new Outcome(repairs, List.of());
            var target = violations.getFirst();
            var plans = remedies.stream().flatMap(r -> r.plans(target, program, rules).stream()).toList();
            String kept = null;
            for (var plan : plans) {
                if (branches++ >= MAX_BRANCHES) return new Outcome(repairs, violations);
                var saved = program.checkpoint();
                try {
                    plan.apply().accept(program);
                } catch (RuntimeException failed) {
                    program.rollback(saved);
                    continue;
                }
                if (!same(target, verifier.verify(baseline, program.graph()))) {
                    kept = plan.title();
                    break;
                }
                program.rollback(saved);
            }
            if (kept == null) return new Outcome(repairs, violations);
            repairs.add(kept);
        }
        return new Outcome(repairs, verifier.verify(baseline, program.graph()));
    }

    /** Whether the violation is still among {@code violations} (same code, site and target). */
    private static boolean same(Violation violation, List<Violation> violations) {
        return violations.stream().anyMatch(v -> v.code().equals(violation.code())
                && java.util.Objects.equals(v.site(), violation.site()) && java.util.Objects.equals(v.target(), violation.target()));
    }
}
