package ide.engine;

import ide.projection.Diagnostic;

import java.util.List;
import java.util.Map;

/**
 * The code still compiles, if it compiled before: each compiler error (not a warning) is a violation, with the facts a remedy needs
 * (the symbol, the file, the line). The costliest view.
 */
public final class CompilerView implements View {

    @Override
    public List<Violation> check(Comparison comparison) {
        if (!comparison.compiledBefore()) return List.of();
        return comparison.projection().diagnostics().stream().filter(Diagnostic::isError).map(d -> new Violation(d.code(), null, null,
                d.path() + ":" + d.line() + ": " + d.message(),
                Map.of("symbol", d.symbol(), "path", d.path(), "line", String.valueOf(d.line())))).toList();
    }

    @Override
    public boolean costly() {
        return true;
    }
}
