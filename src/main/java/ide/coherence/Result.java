package ide.coherence;

import java.util.List;

/** Outcome of a transaction: applied (possibly after repairs), or rolled back because of violations. */
public record Result(boolean applied, List<Violation> violations, List<String> repairs) {

    public static Result ok(List<String> repairs) {
        return new Result(true, List.of(), List.copyOf(repairs));
    }

    public static Result aborted(List<Violation> violations) {
        return new Result(false, List.copyOf(violations), List.of());
    }
}
