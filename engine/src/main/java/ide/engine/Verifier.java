package ide.engine;

import java.util.ArrayList;
import java.util.List;

/** Looks at a change through its views: the cheap ones first, the costly ones only when the cheap see nothing. */
public final class Verifier {

    private final List<View> views;

    public Verifier(List<View> views) {
        this.views = List.copyOf(views);
    }

    /** Binding, access, dispatch, and the compiler last. */
    public static Verifier standard() {
        return new Verifier(List.of(new BindingView(), new AccessView(), new DispatchView(), new CompilerView()));
    }

    public List<Violation> verify(Comparison comparison) {
        var violations = new ArrayList<Violation>();
        for (var view : views) if (!view.costly()) violations.addAll(view.check(comparison));
        if (!violations.isEmpty()) return violations;
        for (var view : views) if (view.costly()) violations.addAll(view.check(comparison));
        return violations;
    }
}
