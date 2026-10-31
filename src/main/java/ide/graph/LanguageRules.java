package ide.graph;

import java.util.List;

/** Language semantics the language-neutral layers ask about. */
public interface LanguageRules {

    /** Whether code inside {@code site} may access {@code target}. */
    boolean accessible(ProgramGraph graph, ElementId site, ElementId target);

    /** Visibility values from most to least restrictive. */
    List<String> visibilities();
}
