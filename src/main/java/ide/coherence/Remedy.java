package ide.coherence;

import ide.graph.LanguageRules;
import ide.graph.Program;

import java.util.List;

/** Proposes plans that may remove a violation, cheapest first. */
public interface Remedy {

    List<Plan> plans(Violation violation, Program program, LanguageRules rules);
}
