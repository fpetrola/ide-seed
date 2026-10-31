package ide.engine;

import ide.program.Program;
import ide.projection.LanguageRules;
import ide.projection.Projection;

/**
 * What a view compares: the program a change meant to leave ({@code baseline}: the program before, with what the
 * change declared folded in), and the projection as the change left it.
 *
 * @param compiledBefore whether the code compiled before the change; only then is it held to compile after
 */
public record Comparison(Program baseline, Projection projection, boolean compiledBefore) {

    public Program after() {
        return projection.program();
    }

    public LanguageRules rules() {
        return projection.rules();
    }
}
