package ide.java;

import ide.graph.Element;
import ide.graph.ElementId;
import ide.graph.LanguageRules;
import ide.graph.ProgramGraph;

import java.util.List;

/** Java semantics: access control. */
public final class JavaRules implements LanguageRules {

    @Override
    public boolean accessible(ProgramGraph graph, ElementId site, ElementId target) {
        var element = graph.element(target);
        return switch (element.attribute("visibility")) {
            case "public" -> true;
            case "private" -> graph.topLevel(site).id().equals(graph.topLevel(target).id());
            case "package" -> samePackage(graph, site, target);
            case "protected" -> samePackage(graph, site, target)
                    || graph.isSubclass(graph.enclosingType(site), graph.enclosingType(target));
            default -> throw new IllegalStateException("unknown visibility of " + element);
        };
    }

    @Override
    public List<String> visibilities() {
        return List.of("private", "package", "protected", "public");
    }

    private static boolean samePackage(ProgramGraph graph, ElementId a, ElementId b) {
        return packageOf(graph.topLevel(a)).equals(packageOf(graph.topLevel(b)));
    }

    private static String packageOf(Element topLevel) {
        var qualified = topLevel.qualified();
        var dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }
}
