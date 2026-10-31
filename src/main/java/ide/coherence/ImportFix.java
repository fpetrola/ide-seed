package ide.coherence;

import ide.graph.Diagnostic;
import ide.graph.LanguageRules;
import ide.graph.Program;

import java.util.List;

/** An unresolved type name: import a type with that simple name. */
public final class ImportFix implements QuickFix {

    private static final String PREFIX = "import ";

    @Override
    public List<String> titles(Diagnostic diagnostic, Program program, LanguageRules rules) {
        if (!diagnostic.code().startsWith("javac.cant.resolve") || diagnostic.symbol().isEmpty()
                || !Character.isUpperCase(diagnostic.symbol().charAt(0))) {
            return List.of();
        }
        return rules.importCandidates(program.graph(), diagnostic.symbol()).stream().map(c -> PREFIX + c).toList();
    }

    @Override
    public void apply(String title, Diagnostic diagnostic, Program program) {
        var unit = program.graph().typesIn(diagnostic.path()).getFirst();
        program.set(unit.id(), "import", title.substring(PREFIX.length()));
    }
}
