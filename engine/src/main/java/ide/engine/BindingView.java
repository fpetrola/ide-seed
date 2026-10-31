package ide.engine;

import ide.program.Program;
import ide.program.Reference;
import ide.projection.LanguageRules;

import java.util.List;

/**
 * Every use the change did not mean to change still binds to the same element. A use that binds something else by
 * the same name is rebound; one that binds nothing is unresolved, unless only access hides its target (then it is an
 * access violation, which a repair can widen).
 */
public final class BindingView implements View {

    @Override
    public List<Violation> check(Comparison comparison) {
        var baseline = comparison.baseline();
        var after = comparison.after();
        var diff = baseline.diff(after);
        return diff.lost().stream().map(ref -> lost(ref, diff.gained(), baseline, after, comparison.rules())).toList();
    }

    private static Violation lost(Reference ref, List<Reference> gained, Program baseline, Program after, LanguageRules rules) {
        var rebound = gained.stream().filter(g -> g.site().equals(ref.site()) && g.name().equals(ref.name())).findFirst();
        if (rebound.isPresent()) {
            return new Violation("binding.rebound", ref.site(), ref.target(), after.element(ref.site()).qualified()
                    + " now binds " + ref.name() + " to " + after.element(rebound.get().target()).qualified()
                    + " instead of " + baseline.element(ref.target()).qualified());
        }
        if (after.contains(ref.site()) && after.contains(ref.target()) && inScope(after, ref)
                && !rules.accessible(after, ref.site(), ref.target())) {
            return AccessView.inaccessible(ref, after);
        }
        var site = (after.contains(ref.site()) ? after : baseline).element(ref.site()).qualified();
        return new Violation("binding.unresolved", ref.site(), ref.target(),
                site + " no longer binds to " + baseline.element(ref.target()).qualified());
    }

    /** Whether the target is a member of the site's type or of one of its supertypes (so only access can hide it). */
    private static boolean inScope(Program program, Reference ref) {
        return program.isSubclass(program.enclosingType(ref.site()), program.enclosingType(ref.target()));
    }
}
