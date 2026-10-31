package ide.coherence;

import ide.graph.LanguageRules;
import ide.graph.Program;
import ide.graph.ProgramGraph;

import java.util.ArrayList;
import java.util.List;

/** Verifies, and while violations remain applies one remedy per round until a fixpoint or the round cap. */
public final class Repairer {

    static final int MAX_ROUNDS = 10;

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
        for (var round = 0; round < MAX_ROUNDS; round++) {
            var violations = verifier.verify(baseline, program.graph());
            if (violations.isEmpty()) return new Outcome(repairs, List.of());
            var repaired = remedies.stream()
                    .flatMap(remedy -> remedy.apply(violations.getFirst(), program, rules).stream())
                    .findFirst();
            if (repaired.isEmpty()) return new Outcome(repairs, violations);
            repairs.add(repaired.get());
        }
        return new Outcome(repairs, verifier.verify(baseline, program.graph()));
    }
}
