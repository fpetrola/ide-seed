package ide.coherence;

import ide.graph.Diagnostic;
import ide.graph.LanguageRules;
import ide.graph.Program;

import java.util.List;

/** Proposes changes that should make a diagnostic disappear; the fix transaction checks that it did. */
public interface QuickFix {

    List<String> titles(Diagnostic diagnostic, Program program, LanguageRules rules);

    void apply(String title, Diagnostic diagnostic, Program program);
}
