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
    public List<String> importCandidates(ProgramGraph graph, String simpleName) {
        var candidates = new java.util.ArrayList<String>();
        graph.types().stream().filter(t -> t.name().equals(simpleName) && t.attribute("file") != null)
                .forEach(t -> candidates.add(t.qualified()));
        candidates.addAll(JdkTypes.named(simpleName));
        return candidates;
    }

    @Override
    public List<String> visibilities() {
        return List.of("private", "package", "protected", "public");
    }

    /** Top-level types are package-private or public; members may be anything. */
    @Override
    public List<String> visibilities(ProgramGraph graph, ElementId element) {
        var e = graph.element(element);
        var topLevel = e.kind().isType() && e.parent() != null && graph.element(e.parent()).kind() == ide.graph.Kind.PACKAGE;
        return topLevel ? List.of("package", "public") : visibilities();
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
