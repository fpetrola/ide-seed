package ide.coherence;

import java.util.List;

/** Outcome of a transaction: applied, or rolled back because of violations. */
public record Result(boolean applied, List<Violation> violations) {

    public static Result ok() {
        return new Result(true, List.of());
    }

    public static Result aborted(List<Violation> violations) {
        return new Result(false, List.copyOf(violations));
    }
}
