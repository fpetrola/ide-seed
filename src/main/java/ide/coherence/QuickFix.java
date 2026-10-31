package ide.coherence;

import ide.graph.Diagnostic;
import ide.graph.LanguageRules;
import ide.graph.Program;

import java.util.List;

/** Proposes plans that should make a diagnostic disappear; the fix transaction checks that it did. */
public interface QuickFix {

    List<Plan> plans(Diagnostic diagnostic, Program program, LanguageRules rules);
}
