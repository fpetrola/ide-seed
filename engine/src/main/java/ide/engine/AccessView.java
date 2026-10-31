package ide.engine;

import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Program;
import ide.program.Reference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Every use can reach what it uses. What could not be reached before the change is not the change's doing. */
public final class AccessView implements View {

    @Override
    public List<Violation> check(Comparison comparison) {
        var rules = comparison.rules();
        var baseline = comparison.baseline();
        var after = comparison.after();
        var alreadyBroken = new HashSet<List<ElementId>>();
        for (var ref : baseline.references()) {
            if (baseline.contains(ref.site()) && baseline.contains(ref.target()) && !rules.accessible(baseline, ref.site(), ref.target())) {
                alreadyBroken.add(List.of(ref.site(), ref.target()));
            }
        }
        var violations = new ArrayList<Violation>();
        for (var ref : after.references()) {
            if (alreadyBroken.contains(List.of(ref.site(), ref.target()))) continue;
            if (!rules.accessible(after, ref.site(), ref.target())) violations.add(inaccessible(ref, after));
        }
        return violations;
    }

    static Violation inaccessible(Reference ref, Program program) {
        var target = program.element(ref.target());
        return new Violation("access.insufficient", ref.site(), ref.target(), program.element(ref.site()).qualified()
                + " cannot access " + target.fact(Fact.VISIBILITY) + " " + target.qualified());
    }
}
