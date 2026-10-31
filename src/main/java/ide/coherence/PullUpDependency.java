package ide.coherence;

import ide.graph.Kind;
import ide.graph.LanguageRules;
import ide.graph.Program;

import java.util.Optional;

/**
 * For {@code binding.unresolved} where a member moved up to a supertype lost a member of a subtype it used:
 * moves that dependency up too, just before its user when the user is a field (initializers run in order).
 */
public final class PullUpDependency implements Remedy {

    @Override
    public Optional<String> apply(Violation violation, Program program, LanguageRules rules) {
        if (!violation.code().equals("binding.unresolved")) return Optional.empty();
        var graph = program.graph();
        var site = graph.element(violation.site());
        var dependency = graph.element(violation.target());
        var to = graph.enclosingType(site.id());
        var from = graph.element(dependency.parent());
        if (from.id().equals(to.id()) || !graph.isSubclass(from, to)) return Optional.empty();
        var signature = dependency.qualified().substring(dependency.qualified().indexOf('#'));
        if (graph.find(to.qualified() + signature).isPresent()) return Optional.empty();
        program.move(dependency.id(), to.id(), site.kind() == Kind.FIELD ? site.id() : null);
        return Optional.of("pull-up-dependency " + dependency.qualified() + " -> " + to.qualified());
    }
}
