package ide.coherence;

import ide.graph.LanguageRules;
import ide.graph.Program;

import java.util.Optional;

/** For {@code access.insufficient}: widens the target to the least visibility that grants the access. */
public final class OpenVisibility implements Remedy {

    @Override
    public Optional<String> apply(Violation violation, Program program, LanguageRules rules) {
        if (!violation.code().equals("access.insufficient")) return Optional.empty();
        var graph = program.graph();
        var target = graph.element(violation.target());
        var current = target.attribute("visibility");
        var candidates = rules.visibilities();
        for (var visibility : candidates.subList(candidates.indexOf(current) + 1, candidates.size())) {
            var hypothetical = graph.with(target.id(), "visibility", visibility);
            if (rules.accessible(hypothetical, violation.site(), target.id())) {
                program.set(target.id(), "visibility", visibility);
                return Optional.of("open-visibility " + target.qualified() + " " + current + " -> " + visibility);
            }
        }
        return Optional.empty();
    }
}
