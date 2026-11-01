package ide.engine;

import ide.projection.Diagnostic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The change adds no compiler error (a warning is not one): each error that was not there before is a violation, with
 * the facts a remedy needs, as a diagnostic gives them (the symbol, the file, the line and column, what it names). Code that did not compile is held to this too, so a
 * project with a problem anywhere is still checked. An error is the one there before if its file, code and message
 * are the same, whatever its line (a change moves lines); as many as there were. The costliest view.
 */
public final class CompilerView implements View {

    @Override
    public List<Violation> check(Comparison comparison) {
        var before = new HashMap<List<String>, Integer>();
        comparison.errorsBefore().forEach(d -> before.merge(key(d), 1, Integer::sum));
        var violations = new ArrayList<Violation>();
        for (var d : comparison.projection().diagnostics()) {
            if (!d.isError()) continue;
            if (before.computeIfPresent(key(d), (k, n) -> n - 1) instanceof Integer left && left >= 0) continue;
            violations.add(new Violation(d.code(), null, null, d.path() + ":" + d.line() + ": " + d.message(),
                    Map.of("symbol", d.symbol(), "path", d.path(), "line", String.valueOf(d.line()),
                            "column", String.valueOf(d.column()), "arguments", d.arguments())));
        }
        return violations;
    }

    private static List<String> key(Diagnostic d) {
        return List.of(d.path(), d.code(), d.message());
    }

    @Override
    public boolean costly() {
        return true;
    }
}
