package ide.coherence;

import java.util.List;

/**
 * Outcome of a transaction: applied (possibly after repairs), or rolled back because of violations.
 * {@code steps} is the tree of what ran: {@code .} call, {@code '} primitive, {@code !} repair; two spaces per level.
 * {@code search} is how the repairer looked for repairs ({@code ?} violation, {@code !} kept, {@code X} undone).
 */
public record Result(boolean applied, List<Violation> violations, List<String> repairs, List<String> steps,
                     List<String> search) {

    public static Result ok(List<String> repairs, List<String> steps, List<String> search) {
        return new Result(true, List.of(), List.copyOf(repairs), List.copyOf(steps), List.copyOf(search));
    }

    public static Result aborted(List<Violation> violations, List<String> steps, List<String> search) {
        return new Result(false, List.copyOf(violations), List.of(), List.copyOf(steps), List.copyOf(search));
    }
}
