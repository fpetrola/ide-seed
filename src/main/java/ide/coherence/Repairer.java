package ide.coherence;

import ide.graph.LanguageRules;
import ide.graph.Program;
import ide.graph.ProgramGraph;

import java.util.ArrayList;
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
     * Repairs applied, the violations left (empty if the transaction can close), and the search as readable
     * lines: {@code ?} violation, {@code !} plan kept, {@code X} plan undone and why.
     */
    public record Outcome(List<String> repairs, List<Violation> violations, List<String> search) {
    }

    private final Verifier verifier;
    private final LanguageRules rules;
    private final List<Remedy> remedies;

    public Repairer(LanguageRules rules, List<Remedy> remedies) {
        this.verifier = new Verifier(rules);
        this.rules = rules;
        this.remedies = List.copyOf(remedies);
    }

    /**
     * @param compiled whether the program compiled before the transaction; only then is it held to compile after
     *                 (the compiler view runs once the graph views are clean, it is the slowest)
     */
    public Outcome stabilize(ProgramGraph baseline, boolean compiled, Program program) {
        return stabilize(baseline, compiled, program, Map.of());
    }

    /**
     * @param pinned what the transaction changed on purpose, per element: "parent", "name" and/or "visibility".
     *               A plan that changes a pinned aspect would undo the refactoring's intent, so it is undone instead.
     */
    public Outcome stabilize(ProgramGraph baseline, boolean compiled, Program program,
                             Map<ide.graph.ElementId, java.util.Set<String>> pinned) {
        var repairs = new ArrayList<String>();
        var search = new ArrayList<String>();
        var branches = 0;
        for (var round = 0; round < MAX_ROUNDS; round++) {
            var violations = verify(baseline, compiled, program);
            if (violations.isEmpty()) return new Outcome(repairs, List.of(), search);
            var target = violations.getFirst();
            search.add("? " + target.code() + " " + target.detail());
            var plans = remedies.stream().flatMap(r -> r.plans(target, program, rules).stream())
                    .filter(plan -> plan.asks().isEmpty()).toList(); // holes are for the user to fill, never guessed
            String kept = null;
            if (plans.isEmpty()) search.add("  (no plan)");
            for (var plan : plans) {
                if (branches++ >= MAX_BRANCHES) {
                    search.add("  X branch cap reached");
                    return new Outcome(repairs, violations, search);
                }
                var saved = program.checkpoint();
                var intent = state(program.graph(), pinned);
                try {
                    plan.apply(program);
                } catch (RuntimeException failed) {
                    program.rollback(saved);
                    search.add("  X " + plan.title() + ": " + String.valueOf(failed.getMessage()).lines().findFirst().orElse(""));
                    continue;
                }
                if (!state(program.graph(), pinned).equals(intent)) {
                    program.rollback(saved);
                    search.add("  X " + plan.title() + ": would undo what the refactoring did");
                    continue;
                }
                if (!same(target, verify(baseline, compiled, program))) {
                    kept = plan.title();
                    search.add("  ! " + plan.title());
                    break;
                }
                program.rollback(saved);
                search.add("  X " + plan.title() + ": still " + target.code());
            }
            if (kept == null) return new Outcome(repairs, violations, search);
            repairs.add(kept);
        }
        return new Outcome(repairs, verify(baseline, compiled, program), search);
    }

    private static Map<String, String> state(ProgramGraph graph, Map<ide.graph.ElementId, java.util.Set<String>> pinned) {
        var state = new java.util.HashMap<String, String>();
        pinned.forEach((id, aspects) -> {
            if (!graph.contains(id)) return;
            var e = graph.element(id);
            for (var aspect : aspects) {
                var value = switch (aspect) {
                    case "parent" -> String.valueOf(e.parent());
                    case "name" -> e.name();
                    default -> e.attribute(aspect);
                };
                state.put(id + "." + aspect, value);
            }
        });
        return state;
    }

    private List<Violation> verify(ProgramGraph baseline, boolean compiled, Program program) {
        var violations = verifier.verify(baseline, program.graph());
        if (!violations.isEmpty() || !compiled) return violations;
        return program.diagnostics().stream().map(d -> new Violation(d.code(), null, null,
                d.path() + ":" + d.line() + ": " + d.message(),
                Map.of("symbol", d.symbol(), "path", d.path(), "line", String.valueOf(d.line())))).toList();
    }

    /** Whether the violation is still among {@code violations} (same code, site and target). */
    private static boolean same(Violation violation, List<Violation> violations) {
        return violations.stream().anyMatch(v -> v.code().equals(violation.code())
                && java.util.Objects.equals(v.site(), violation.site()) && java.util.Objects.equals(v.target(), violation.target())
                && v.facts().equals(violation.facts()));
    }
}
