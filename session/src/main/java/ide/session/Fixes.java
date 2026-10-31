package ide.session;

import ide.engine.Trace;
import ide.knowledge.Knowledge;
import ide.projection.Diagnostic;
import ide.projection.Projection;

import java.util.List;

/** The quick fixes for the diagnostics on a line: what is offered, what each asks, and how they were deduced. */
public final class Fixes {

    private final Projection projection;
    private final Knowledge knowledge;

    public Fixes(Projection projection, Knowledge knowledge) {
        this.projection = projection;
        this.knowledge = knowledge;
    }

    /** The titles of the fixes offered for the diagnostics on a line. */
    public List<String> titles(String path, int line) {
        return found(path, line).stream().map(fix -> fix.plan().title()).distinct().toList();
    }

    /** The questions a fix asks: {@code name: question [default]}. */
    public List<String> asks(String path, int line, String title) {
        return find(path, line, title).plan().asks().stream()
                .map(ask -> ask.name() + ": " + ask.question() + " [" + ask.defaultAnswer() + "]").toList();
    }

    /** The fix with that title for a diagnostic on the line. */
    public Fix find(String path, int line, String title) {
        return found(path, line).stream().filter(fix -> fix.plan().title().equals(title)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no fix '" + title + "' at " + path + ":" + line));
    }

    /** How the fixes for the diagnostics on a line are deduced: each diagnostic, its goals, and the effects tried. */
    public Trace why(String path, int line) {
        var trace = new Trace();
        for (var diagnostic : diagnostics(path, line)) {
            trace.add(Trace.Kind.NOTE, diagnostic.code() + " " + diagnostic.symbol());
            trace.append(knowledge.search(diagnostic, projection).trace());
        }
        return trace;
    }

    private List<Fix> found(String path, int line) {
        return diagnostics(path, line).stream()
                .flatMap(diagnostic -> knowledge.plans(diagnostic, projection).stream().map(plan -> new Fix(diagnostic, plan)))
                .toList();
    }

    private List<Diagnostic> diagnostics(String path, int line) {
        return projection.diagnostics().stream().filter(d -> d.path().equals(path) && d.line() == line).toList();
    }
}
