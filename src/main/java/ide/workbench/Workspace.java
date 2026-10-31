package ide.workbench;

import ide.core.EventLog;
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
import ide.refactor.MoveType;
import ide.refactor.PullUp;
import ide.refactor.Refactoring;
import ide.refactor.Rename;
import ide.refactor.RenameMethodFamily;

import java.nio.file.Path;
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
                    new GenerateGetter(), new GenerateSetter(), new EncapsulateField(), new MoveType())
            .collect(Collectors.toMap(Refactoring::id, Function.identity()));

    private final java.util.Deque<Object> undo = new java.util.ArrayDeque<>();
    private EventLog log = EventLog.NONE;

    private Workspace(JavaProject project) {
        this.project = project;
    }

    public static Workspace of(Map<String, String> sources) {
        return new Workspace(JavaProject.of(sources));
    }

    public String source(String path) {
        return project.source(path);
    }

    /** Appends every event to a JSONL file from now on. */
    public Workspace logTo(Path file) {
        log = EventLog.to(file);
        return this;
    }

    /** Reverts the last applied transaction. */
    public void undo() {
        if (undo.isEmpty()) throw new IllegalStateException("nothing to undo");
        project.rollback(undo.pop());
        log.append(Map.of("event", "undo"));
    }

    public java.util.Set<String> files() {
        return project.files();
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
        Result result;
        try {
            ctx.call(refactoring, args);
            result = tx.commit(repairer);
        } catch (PrimitiveRejected rejected) {
            result = tx.abort(new Violation(rejected.code(), null, null, rejected.getMessage()));
        }
        return record(tx, Map.of("event", "transaction", "refactoring", refactoring, "args", args), result);
    }

    /** Logs a finished transaction and makes it undoable if it applied. */
    private Result record(Transaction tx, Map<String, ?> event, Result result) {
        if (result.applied()) undo.push(tx.before());
        var entry = new java.util.LinkedHashMap<String, Object>(event);
        entry.put("applied", result.applied());
        entry.put("violations", result.violations().stream().map(v -> v.code() + ": " + v.detail()).toList());
        entry.put("repairs", result.repairs());
        entry.put("steps", result.steps());
        log.append(entry);
        return result;
    }

    /**
     * Completion at {@code offset} of the editor text of {@code path}: accessible fields ({@code x}) and methods
     * ({@code m()}) of the receiver and its superclasses, nearest first, overridden methods once.
     */
    public List<String> complete(String path, String text, int offset) {
        var context = project.completionContext(path, text, offset);
        if (context.isEmpty()) return List.of();
        var graph = project.graph();
        var labels = new java.util.LinkedHashSet<String>();
        for (var type : graph.superclassChain(context.get().receiver())) {
            for (var member : graph.members(type.id())) {
                if (!member.name().startsWith(context.get().prefix())) continue;
                if (!rules.accessible(graph, context.get().site(), member.id())) continue;
                labels.add(member.kind() == ide.graph.Kind.METHOD ? member.name() + "()" : member.name());
            }
        }
        return List.copyOf(labels);
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
                var event = Map.of("event", "fix", "path", path, "line", line, "title", title);
                if (project.diagnostics().contains(diagnostic)) {
                    return record(tx, event, tx.abort(new Violation("fix.ineffective", null, null, title + " left " + diagnostic)));
                }
                return record(tx, event, tx.commit(repairer));
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
