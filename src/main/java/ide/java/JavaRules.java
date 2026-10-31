package ide.java;

import ide.graph.ElementId;
import ide.graph.LanguageRules;
import ide.graph.ProgramGraph;

/** Java semantics: access control (only {@code private} for now). */
public final class JavaRules implements LanguageRules {

    @Override
    public boolean accessible(ProgramGraph graph, ElementId site, ElementId target) {
        if (!"private".equals(graph.element(target).attribute("visibility"))) return true;
        return graph.topLevel(site).id().equals(graph.topLevel(target).id());
    }
}
