package ide.coherence;

import ide.graph.LanguageRules;
import ide.graph.ProgramGraph;
import ide.graph.Reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Compares the graph after a transaction against its baseline and reports broken invariants. */
public final class Verifier {

    private final LanguageRules rules;

    public Verifier(LanguageRules rules) {
        this.rules = rules;
    }

    public List<Violation> verify(ProgramGraph baseline, ProgramGraph after) {
        var violations = new ArrayList<Violation>();
        var remaining = new HashMap<Reference, Integer>();
        after.references().forEach(ref -> remaining.merge(ref, 1, Integer::sum));
        for (var ref : baseline.references()) {
            if (remaining.merge(ref, -1, Integer::sum) >= 0) continue;
            violations.add(lost(ref, baseline, after));
        }
        for (var ref : after.references()) {
            if (!rules.accessible(after, ref.site(), ref.target())) violations.add(inaccessible(ref, after));
        }
        return violations;
    }

    /** A baseline reference that no longer binds to the same element. */
    private Violation lost(Reference ref, ProgramGraph baseline, ProgramGraph after) {
        if (after.contains(ref.site()) && after.contains(ref.target())
                && !rules.accessible(after, ref.site(), ref.target())) {
            return inaccessible(ref, after);
        }
        var site = baseline.element(ref.site()).qualified();
        return new Violation("binding.unresolved", site + " no longer binds to " + baseline.element(ref.target()).qualified());
    }

    private static Violation inaccessible(Reference ref, ProgramGraph graph) {
        var target = graph.element(ref.target());
        return new Violation("access.insufficient", graph.element(ref.site()).qualified()
                + " cannot access " + target.attribute("visibility") + " " + target.qualified());
    }
}
