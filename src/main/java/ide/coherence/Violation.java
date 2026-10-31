package ide.coherence;

import ide.graph.ElementId;

/** A broken invariant, identified by a stable {@code view.kind} code, about a use of {@code target} from {@code site}. */
public record Violation(String code, ElementId site, ElementId target, String detail) {
}
