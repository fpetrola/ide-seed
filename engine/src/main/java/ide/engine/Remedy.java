package ide.engine;

import ide.projection.Projection;

import java.util.List;

/** Proposes plans that may remove a violation, cheapest first; the projection brings the language's rules. */
public interface Remedy {

    List<Plan> plans(Violation violation, Projection projection);
}
