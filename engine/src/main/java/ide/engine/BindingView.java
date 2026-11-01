package ide.engine;

import ide.program.ElementId;
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
                    + " instead of " + qualified(ref.target(), baseline, after));
        }
        if (after.contains(ref.site()) && after.contains(ref.target()) && inScope(after, ref)
                && !rules.accessible(after, ref.site(), ref.target())) {
            return AccessView.inaccessible(ref, after);
        }
        return new Violation("binding.unresolved", ref.site(), ref.target(),
                qualified(ref.site(), after, baseline) + " no longer binds to " + qualified(ref.target(), baseline, after));
    }

    /** An element as one program names it, or the other: a change may have meant one born with it, or gone. */
    private static String qualified(ElementId element, Program first, Program second) {
        if (!first.contains(element) && !second.contains(element)) return element.value(); // born and gone within the change
        return (first.contains(element) ? first : second).element(element).qualified();
    }

    /** Whether the target is a member of the site's type or of one of its supertypes (so only access can hide it). */
    private static boolean inScope(Program program, Reference ref) {
        return program.isSubclass(program.enclosingType(ref.site()), program.enclosingType(ref.target()));
    }
}
