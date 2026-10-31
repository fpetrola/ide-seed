package ide.engine;

import java.util.List;

/**
 * One way of seeing that a change broke something, by comparing what the change meant to leave with what it left.
 * Adding a check is adding a view.
 */
public interface View {

    List<Violation> check(Comparison comparison);

    /** A costly view runs only when the cheap ones see nothing (the compiler, which is slow and repeats them). */
    default boolean costly() {
        return false;
    }
}
