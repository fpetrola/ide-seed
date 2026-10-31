package ide.workbench;

import ide.coherence.ImportFix;
import ide.coherence.OpenVisibility;
import ide.coherence.QuickFix;
import ide.coherence.PullUpDependency;
import ide.coherence.Repairer;
import ide.coherence.Result;
import ide.coherence.Transaction;
import ide.coherence.Violation;
import ide.graph.Diagnostic;
import ide.graph.LanguageRules;
import ide.graph.PrimitiveRejected;
import ide.graph.Program;
import ide.java.JavaProject;
import ide.java.JavaRules;
import ide.refactor.Args;
import ide.refactor.Ctx;
import ide.refactor.EncapsulateField;
import ide.refactor.ExtractSuperclass;
import ide.refactor.GenerateGetter;
import ide.refactor.GenerateSetter;
import ide.refactor.PullUp;
import ide.refactor.Refactoring;
import ide.refactor.Rename;
import ide.refactor.RenameMethodFamily;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Entry point: invokes refactorings as transactions over a Java project. */
public final class Workspace {

    private final JavaProject project;
    private final LanguageRules rules = new JavaRules();
    private final Repairer repairer = new Repairer(rules, List.of(new OpenVisibility(), new PullUpDependency()));
    private final List<QuickFix> quickFixes = List.of(new ImportFix());
    private final Map<String, Refactoring> refactorings = Stream.of(new Rename(), new RenameMethodFamily(), PullUp.field(), PullUp.method(), new ExtractSuperclass(),
                    new GenerateGetter(), new GenerateSetter(), new EncapsulateField())
            .collect(Collectors.toMap(Refactoring::id, Function.identity()));

    private Workspace(JavaProject project) {
        this.project = project;
    }

    public static Workspace of(Map<String, String> sources) {
        return new Workspace(JavaProject.of(sources));
    }

    public String source(String path) {
        return project.source(path);
    }

    /** Runs a refactoring naively in a transaction, then verifies and repairs; rolls back if violations remain. */
    public Result invoke(String refactoring, Map<String, ?> args) {
        var tx = new Transaction(project);
        var ctx = new Ctx() {
            @Override
            public Program program() {
                return tx;
            }

            @Override
            public String call(String id, Map<String, ?> callArgs) {
                tx.enter(id);
                try {
                    return refactoring(id).apply(this, new Args(callArgs));
                } finally {
                    tx.exit();
                }
            }
        };
        try {
            ctx.call(refactoring, args);
        } catch (PrimitiveRejected rejected) {
            return tx.abort(new Violation(rejected.code(), null, null, rejected.getMessage()));
        }
        return tx.commit(repairer);
    }

    public List<Diagnostic> diagnostics() {
        return project.diagnostics();
    }

    /** Titles of the fixes offered for the diagnostics on a line. */
    public List<String> fixes(String path, int line) {
        return diagnosticsAt(path, line).stream()
                .flatMap(d -> quickFixes.stream().flatMap(f -> f.titles(d, project, rules).stream()))
                .distinct().toList();
    }

    /** Applies a fix as a transaction that must make its diagnostic disappear. */
    public Result fix(String path, int line, String title) {
        for (var diagnostic : diagnosticsAt(path, line)) {
            for (var quickFix : quickFixes) {
                if (!quickFix.titles(diagnostic, project, rules).contains(title)) continue;
                var tx = new Transaction(project);
                tx.enter("fix " + title);
                quickFix.apply(title, diagnostic, tx);
                tx.exit();
                if (project.diagnostics().contains(diagnostic)) {
                    return tx.abort(new Violation("fix.ineffective", null, null, title + " left " + diagnostic));
                }
                return tx.commit(repairer);
            }
        }
        throw new IllegalArgumentException("no fix '" + title + "' at " + path + ":" + line);
    }

    private List<Diagnostic> diagnosticsAt(String path, int line) {
        return project.diagnostics().stream().filter(d -> d.path().equals(path) && d.line() == line).toList();
    }

    private Refactoring refactoring(String id) {
        var refactoring = refactorings.get(id);
        if (refactoring == null) throw new IllegalArgumentException("unknown refactoring: " + id);
        return refactoring;
    }
}
