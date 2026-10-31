package ide.engine;

import ide.program.ElementId;
import ide.projection.LanguageRules;
import ide.program.Program;
import ide.program.Reference;

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

    public List<Violation> verify(Program baseline, Program after) {
        var violations = new ArrayList<Violation>();
        var lost = minus(baseline.references(), after.references());
        var gained = minus(after.references(), baseline.references());
        for (var ref : lost) violations.add(lost(ref, gained, baseline, after));
        var alreadyBroken = new java.util.HashSet<List<ElementId>>();
        for (var ref : baseline.references()) {
            if (baseline.contains(ref.site()) && baseline.contains(ref.target())
                    && !rules.accessible(baseline, ref.site(), ref.target())) {
                alreadyBroken.add(List.of(ref.site(), ref.target()));
            }
        }
        for (var ref : after.references()) {
            if (alreadyBroken.contains(List.of(ref.site(), ref.target()))) continue; // not this transaction's doing
            if (!rules.accessible(after, ref.site(), ref.target())) violations.add(inaccessible(ref, after));
        }
        violations.addAll(dispatch(baseline, after));
        return violations;
    }

    /** References in {@code a} with no counterpart (same site and target; the name may differ) in {@code b}. */
    private static List<Reference> minus(List<Reference> a, List<Reference> b) {
        var available = new HashMap<List<ElementId>, Integer>();
        b.forEach(ref -> available.merge(List.of(ref.site(), ref.target()), 1, Integer::sum));
        var result = new ArrayList<Reference>();
        for (var ref : a) {
            if (available.merge(List.of(ref.site(), ref.target()), -1, Integer::sum) < 0) result.add(ref);
        }
        return result;
    }

    /** Overriding must be the same before and after: no override lost, none gained. */
    private static List<Violation> dispatch(Program baseline, Program after) {
        var before = overrides(baseline);
        var now = overrides(after);
        var violations = new ArrayList<Violation>();
        for (var pair : now) {
            // overriding a method this transaction created changes no existing dispatch: nothing could call it before
            if (!baseline.contains(pair.get(1))) continue;
            if (!before.contains(pair)) violations.add(new Violation("dispatch.new-override", pair.get(0), pair.get(1),
                    after.element(pair.get(0)).qualified() + " would override " + after.element(pair.get(1)).qualified()));
        }
        for (var pair : before) {
            if (!now.contains(pair)) violations.add(new Violation("dispatch.lost-override", pair.get(0), pair.get(1),
                    baseline.element(pair.get(0)).qualified() + " no longer overrides " + baseline.element(pair.get(1)).qualified()));
        }
        return violations;
    }

    /** (method, overridden) pairs. */
    private static java.util.Set<List<ElementId>> overrides(Program graph) {
        var overrides = new java.util.LinkedHashSet<List<ElementId>>();
        for (var method : graph.methods()) graph.overriddenAll(method).forEach(o -> overrides.add(List.of(method.id(), o.id())));
        return overrides;
    }

    /** A baseline reference that no longer binds to the same element. */
    private Violation lost(Reference ref, List<Reference> gained, Program baseline, Program after) {
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
    private static boolean inScope(Program graph, Reference ref) {
        var owner = graph.enclosingType(ref.target());
        return graph.isSubclass(graph.enclosingType(ref.site()), owner);
    }

    private static Violation inaccessible(Reference ref, Program graph) {
        var target = graph.element(ref.target());
        return new Violation("access.insufficient", ref.site(), ref.target(), graph.element(ref.site()).qualified()
                + " cannot access " + target.attribute("visibility") + " " + target.qualified());
    }
}
