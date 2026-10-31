package ide.coherence;

/** A broken invariant, identified by a stable {@code view.kind} code. */
public record Violation(String code, String detail) {
}
