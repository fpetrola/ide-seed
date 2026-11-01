package ide.engine;

import ide.program.ElementId;
import ide.program.Program;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Overriding is the same before and after: no override lost, none gained. Overriding a method the change created
 * changes no dispatch: nothing could call it before. Nor does implementing an abstract one: it had no body to run. Nor does overriding one from a type its calls cannot reach: they
 * were made on receivers of the type it was declared in, and an overrider outside that type's hierarchy never runs
 * for them.
 */
public final class DispatchView implements View {

    @Override
    public List<Violation> check(Comparison comparison) {
        var baseline = comparison.baseline();
        var after = comparison.after();
        var before = overrides(baseline);
        var now = overrides(after);
        var violations = new ArrayList<Violation>();
        for (var pair : now) {
            if (!baseline.contains(pair.get(1)) || before.contains(pair) || abstractIn(baseline, pair.get(1))
                    || unreachable(pair, baseline, after)) continue;
            violations.add(new Violation("dispatch.new-override", pair.get(0), pair.get(1),
                    after.element(pair.get(0)).qualified() + " would override " + after.element(pair.get(1)).qualified()));
        }
        for (var pair : before) {
            if (now.contains(pair)) continue;
            violations.add(new Violation("dispatch.lost-override", pair.get(0), pair.get(1),
                    baseline.element(pair.get(0)).qualified() + " no longer overrides " + baseline.element(pair.get(1)).qualified()));
        }
        return violations;
    }

    /** Whether the overrider's type is outside the hierarchy of the type the overridden method was declared in. */
    private static boolean unreachable(List<ElementId> pair, Program baseline, Program after) {
        var declaredIn = baseline.element(pair.get(1)).parent();
        if (!after.contains(declaredIn)) return false;
        var overrider = after.enclosingType(pair.get(0));
        return !overrider.id().equals(declaredIn) && !after.ancestors(overrider).contains(after.element(declaredIn));
    }

    /** (method, overridden) pairs. */
    private static Set<List<ElementId>> overrides(Program program) {
        var overrides = new LinkedHashSet<List<ElementId>>();
        for (var method : program.methods()) program.overriddenAll(method).forEach(o -> overrides.add(List.of(method.id(), o.id())));
        return overrides;
    }

    private static boolean abstractIn(Program program, ElementId method) {
        return "true".equals(program.element(method).fact(ide.program.Fact.ABSTRACT));
    }
}
