package ide.workbench;

import ide.core.EventLog;
import ide.coherence.Plan;
import ide.coherence.QuickFix;
import ide.knowledge.Knowledge;
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
import ide.refactor.ChangeSignature;
import ide.refactor.Primitives;
import ide.refactor.Refactoring;
import ide.refactor.script.ScriptParser;
import ide.core.Param;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Entry point: invokes refactorings as transactions over a Java project. */
public final class Workspace {

    private final JavaProject project;
    private final LanguageRules rules = new JavaRules();
    private final Knowledge knowledge = Knowledge.parse(resource("/ide/knowledge/knowledge.ref"), this::refactoring);
    private final Repairer repairer = new Repairer(rules, List.of(knowledge));
    private final List<QuickFix> quickFixes = List.of(knowledge);
    private final Map<String, Refactoring> refactorings = new java.util.TreeMap<>();

    private final java.util.Deque<Object> undo = new java.util.ArrayDeque<>();
    private EventLog log = EventLog.NONE;

    private Workspace(JavaProject project) {
        this.project = project;
        Primitives.all().forEach(this::register);
        register(new ChangeSignature());
        define(resource("/ide/refactor/standard.ref"));
    }

    private static String resource(String name) {
        try (var in = Workspace.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Registers the refactorings written in a {@code .ref} text; returns their ids. */
    public List<String> define(String scripts) {
        var defined = ScriptParser.parse(scripts);
        defined.forEach(this::register);
        log.append(Map.of("event", "define", "refactorings", defined.stream().map(Refactoring::id).toList()));
        return defined.stream().map(Refactoring::id).toList();
    }

    /** {@code "<level> <id>"} for every registered refactoring. */
    public List<String> library() {
        return refactorings.values().stream().map(r -> r.level() + " " + r.id()).toList();
    }

    private void register(Refactoring refactoring) {
        refactorings.put(refactoring.id(), refactoring);
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
                var refactoring = refactoring(id);
                if (refactoring.level().equals("primitive")) return refactoring.apply(this, new Args(callArgs));
                tx.enter(id);
                try {
                    return refactoring.apply(this, new Args(callArgs));
                } finally {
                    tx.exit();
                }
            }

            @Override
            public List<Param> params(String id) {
                return refactoring(id).params();
            }

            @Override
            public LanguageRules rules() {
                return rules;
            }

            @Override
            public boolean attempt(String label, Runnable body) {
                var mark = tx.mark();
                tx.enter("try " + label);
                String failure;
                try {
                    body.run();
                    var left = tx.check(repairer);
                    failure = left == null ? null : left.code() + " " + left.detail();
                } catch (PrimitiveRejected rejected) {
                    failure = rejected.code() + " " + rejected.getMessage();
                } finally {
                    tx.exit();
                }
                if (failure == null) return true;
                tx.back(mark);
                tx.discarded(label + ": " + failure);
                return false;
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
                .flatMap(d -> quickFixes.stream().flatMap(f -> f.plans(d, project, rules).stream()))
                .map(Plan::title).distinct().toList();
    }

    /** Applies a fix as a transaction that must make its diagnostic disappear. */
    public Result fix(String path, int line, String title) {
        for (var diagnostic : diagnosticsAt(path, line)) {
            for (var quickFix : quickFixes) {
                for (var plan : quickFix.plans(diagnostic, project, rules)) {
                    if (!plan.title().equals(title)) continue;
                    var tx = new Transaction(project);
                    tx.enter("fix " + title);
                    plan.apply().accept(tx);
                    tx.exit();
                    var event = Map.of("event", "fix", "path", path, "line", line, "title", title);
                    if (project.diagnostics().contains(diagnostic)) {
                        return record(tx, event, tx.abort(new Violation("fix.ineffective", null, null, title + " left " + diagnostic)));
                    }
                    return record(tx, event, tx.commit(repairer));
                }
            }
        }
        throw new IllegalArgumentException("no fix '" + title + "' at " + path + ":" + line);
    }

    /** How fixes for the diagnostics on a line are deduced: {@code ?} goal, {@code !} plan, {@code X} rejected. */
    public List<String> why(String path, int line) {
        return diagnosticsAt(path, line).stream().flatMap(d -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(d.code() + " " + d.symbol()),
                knowledge.search(d.code(), Map.of("symbol", d.symbol(), "path", d.path(), "line", String.valueOf(d.line())),
                        project, rules).lines().stream())).toList();
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
