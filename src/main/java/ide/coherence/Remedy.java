package ide.coherence;

import ide.graph.LanguageRules;
import ide.graph.Program;

import java.util.Optional;

/** Proposes a change that may remove a violation; the verifier decides whether it did. */
public interface Remedy {

    /** Applies the remedy and describes it, or returns empty if it does not apply. */
    Optional<String> apply(Violation violation, Program program, LanguageRules rules);
}
