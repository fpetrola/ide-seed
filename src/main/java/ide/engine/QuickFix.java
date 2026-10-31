package ide.engine;

import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Projection;

import java.util.List;

/** Proposes plans that should make a diagnostic disappear; the fix transaction checks that it did. */
public interface QuickFix {

    List<Plan> plans(Diagnostic diagnostic, Projection projection, LanguageRules rules);
}
