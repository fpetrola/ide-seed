package ide.session;

import ide.engine.Plan;
import ide.projection.Diagnostic;

/** A fix found for a diagnostic: the plan that should make it disappear. */
public record Fix(Diagnostic diagnostic, Plan plan) {
}
