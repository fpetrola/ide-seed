package ide.coherence;

import java.util.List;

/**
 * Outcome of a transaction: applied (possibly after repairs), or rolled back because of violations.
 * {@code steps} is the tree of what ran: {@code .} call, {@code '} primitive, {@code !} repair; two spaces per level.
 * {@code search} is how the repairer looked for repairs ({@code ?} violation, {@code !} kept, {@code X} undone).
 * {@code obligations} are what could not be decided and is assumed; with any, the guarantee is "assumed".
 */
public record Result(boolean applied, List<Violation> violations, List<String> repairs, List<String> steps,
                     List<String> search, List<String> obligations) {

    public static Result ok(List<String> repairs, List<String> steps, List<String> search, List<String> obligations) {
        return new Result(true, List.of(), List.copyOf(repairs), List.copyOf(steps), List.copyOf(search), List.copyOf(obligations));
    }

    public static Result aborted(List<Violation> violations, List<String> steps, List<String> search) {
        return new Result(false, List.copyOf(violations), List.of(), List.copyOf(steps), List.copyOf(search), List.of());
    }

    public String guarantee() {
        return obligations.isEmpty() ? "guaranteed" : "assumed";
    }
}
