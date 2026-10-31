package ide.engine;

import java.util.List;

/**
 * Outcome of a transaction: applied (possibly after repairs), or rolled back because of violations. {@code trace} is
 * what ran (calls, primitives, attempts, repairs); {@code search} is how the repairer looked for repairs.
 * {@code obligations} are what could not be decided and is assumed; with any, the guarantee is "assumed".
 */
public record Result(boolean applied, List<Violation> violations, List<String> repairs, Trace trace, Trace search,
                     List<String> obligations) {

    public static Result ok(List<String> repairs, Trace trace, Trace search, List<String> obligations) {
        return new Result(true, List.of(), List.copyOf(repairs), trace, search, List.copyOf(obligations));
    }

    public static Result aborted(List<Violation> violations, Trace trace, Trace search) {
        return new Result(false, List.copyOf(violations), List.of(), trace, search, List.of());
    }

    /** What ran, written as lines. */
    public List<String> steps() {
        return trace.lines();
    }

    public String guarantee() {
        return obligations.isEmpty() ? "guaranteed" : "assumed";
    }
}
