package ide.graph;

import java.util.List;

/** Language semantics the language-neutral layers ask about. */
public interface LanguageRules {

    /** Whether code inside {@code site} may access {@code target}. */
    boolean accessible(ProgramGraph graph, ElementId site, ElementId target);

    /** Qualified names of the types that could be imported for {@code simpleName}, workspace first. */
    List<String> importCandidates(ProgramGraph graph, String simpleName);

    /** Visibility values from most to least restrictive. */
    List<String> visibilities();
}
