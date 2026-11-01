package ide.engine;

import ide.program.ElementId;

import java.util.Map;

/**
 * A broken invariant, identified by a stable {@code view.kind} code, about a use of {@code target} from {@code site}.
 * {@code facts} carry what else a remedy may need (e.g. the symbol and path of a compiler error).
 */
public record Violation(String code, ElementId site, ElementId target, String detail, Map<String, Object> facts) {

    public Violation(String code, ElementId site, ElementId target, String detail) {
        this(code, site, target, detail, Map.of());
    }
}
