package ide.java;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.projection.LanguageRules;
import ide.program.Program;

import java.util.List;

/** Java semantics: access control. */
public final class JavaRules implements LanguageRules {

    @Override
    public boolean accessible(Program graph, ElementId site, ElementId target) {
        var element = graph.element(target);
        return switch (element.fact(Fact.VISIBILITY)) {
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

    /** Top-level types are package-private or public; members may be anything. */
    @Override
    public List<String> visibilities(Program graph, ElementId element) {
        var e = graph.element(element);
        var topLevel = e.kind().isType() && e.parent() != null && graph.element(e.parent()).kind() == ide.program.Kind.PACKAGE;
        return topLevel ? List.of("package", "public") : visibilities();
    }

    private static boolean samePackage(Program graph, ElementId a, ElementId b) {
        return packageOf(graph.topLevel(a)).equals(packageOf(graph.topLevel(b)));
    }

    private static String packageOf(Element topLevel) {
        var qualified = topLevel.qualified();
        var dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }
}
