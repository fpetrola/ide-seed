package ide.coherence;

import ide.graph.ElementId;
import ide.graph.LanguageRules;
import ide.graph.ProgramGraph;
import ide.graph.Reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        violations.addAll(dispatch(baseline, after));
        return violations;
    }

    /** Overriding must be the same before and after: no override lost, none gained. */
    private static List<Violation> dispatch(ProgramGraph baseline, ProgramGraph after) {
        var before = overrides(baseline);
        var now = overrides(after);
        var violations = new ArrayList<Violation>();
        now.forEach((method, overridden) -> {
            if (!overridden.equals(before.get(method))) violations.add(new Violation("dispatch.new-override", method, overridden,
                    after.element(method).qualified() + " would override " + after.element(overridden).qualified()));
        });
        before.forEach((method, overridden) -> {
            if (!now.containsKey(method)) violations.add(new Violation("dispatch.lost-override", method, overridden,
                    baseline.element(method).qualified() + " no longer overrides " + baseline.element(overridden).qualified()));
        });
        return violations;
    }

    private static Map<ElementId, ElementId> overrides(ProgramGraph graph) {
        var overrides = new HashMap<ElementId, ElementId>();
        for (var method : graph.methods()) graph.overridden(method).ifPresent(o -> overrides.put(method.id(), o.id()));
        return overrides;
    }

    /** A baseline reference that no longer binds to the same element. */
    private Violation lost(Reference ref, ProgramGraph baseline, ProgramGraph after) {
        if (after.contains(ref.site()) && after.contains(ref.target())
                && !rules.accessible(after, ref.site(), ref.target())) {
            return inaccessible(ref, after);
        }
        var site = (after.contains(ref.site()) ? after : baseline).element(ref.site()).qualified();
        return new Violation("binding.unresolved", ref.site(), ref.target(),
                site + " no longer binds to " + baseline.element(ref.target()).qualified());
    }

    private static Violation inaccessible(Reference ref, ProgramGraph graph) {
        var target = graph.element(ref.target());
        return new Violation("access.insufficient", ref.site(), ref.target(), graph.element(ref.site()).qualified()
                + " cannot access " + target.attribute("visibility") + " " + target.qualified());
    }
}
