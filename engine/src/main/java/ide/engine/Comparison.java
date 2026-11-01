package ide.engine;

import ide.program.Program;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Projection;

import java.util.List;

/**
 * What a view compares: the program a change meant to leave ({@code baseline}: the program before, with what the
 * change declared folded in), and the projection as the change left it.
 *
 * @param errorsBefore the compiler's errors before the change: they are not the change's, any other is
 */
public record Comparison(Program baseline, Projection projection, List<Diagnostic> errorsBefore) {

    public Comparison {
        errorsBefore = List.copyOf(errorsBefore);
    }

    /** A comparison where the code compiled before the change. */
    public Comparison(Program baseline, Projection projection) {
        this(baseline, projection, List.of());
    }

    public Program after() {
        return projection.program();
    }

    public LanguageRules rules() {
        return projection.rules();
    }
}
