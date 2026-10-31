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
        var lost = new ArrayList<Reference>();
        for (var ref : baseline.references()) {
            if (remaining.merge(ref, -1, Integer::sum) < 0) lost.add(ref);
        }
        var gained = new ArrayList<Reference>();
        remaining.forEach((ref, count) -> {
            for (var i = 0; i < count; i++) gained.add(ref);
        });
        for (var ref : lost) violations.add(lost(ref, gained, baseline, after));
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
    private Violation lost(Reference ref, List<Reference> gained, ProgramGraph baseline, ProgramGraph after) {
        var rebound = gained.stream()
                .filter(g -> g.site().equals(ref.site()) && g.name().equals(ref.name())).findFirst();
        if (rebound.isPresent()) {
            return new Violation("binding.rebound", ref.site(), ref.target(), after.element(ref.site()).qualified()
                    + " now binds " + ref.name() + " to " + after.element(rebound.get().target()).qualified()
                    + " instead of " + baseline.element(ref.target()).qualified());
        }
        if (after.contains(ref.site()) && after.contains(ref.target()) && inScope(after, ref)
                && !rules.accessible(after, ref.site(), ref.target())) {
            return inaccessible(ref, after);
        }
        var site = (after.contains(ref.site()) ? after : baseline).element(ref.site()).qualified();
        return new Violation("binding.unresolved", ref.site(), ref.target(),
                site + " no longer binds to " + baseline.element(ref.target()).qualified());
    }

    /** Whether the target is a member of the site's type or of one of its supertypes (so only access can hide it). */
    private static boolean inScope(ProgramGraph graph, Reference ref) {
        var owner = graph.enclosingType(ref.target());
        return graph.isSubclass(graph.enclosingType(ref.site()), owner);
    }

    private static Violation inaccessible(Reference ref, ProgramGraph graph) {
        var target = graph.element(ref.target());
        return new Violation("access.insufficient", ref.site(), ref.target(), graph.element(ref.site()).qualified()
                + " cannot access " + target.attribute("visibility") + " " + target.qualified());
    }
}
