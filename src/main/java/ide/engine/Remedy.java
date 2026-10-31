package ide.engine;

import ide.projection.LanguageRules;
import ide.projection.Projection;

import java.util.List;

/** Proposes plans that may remove a violation, cheapest first. */
public interface Remedy {

    List<Plan> plans(Violation violation, Projection projection, LanguageRules rules);
}
