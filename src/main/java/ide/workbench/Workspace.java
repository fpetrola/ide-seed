package ide.workbench;

import ide.core.Capability;
import ide.core.CapabilityRegistry;
import ide.core.EventLog;
import ide.core.TextDiff;
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

    /** The sources as they are on disk (as loaded, or as last saved). */
    private Map<String, String> saved;

    private Workspace(JavaProject project) {
        this.project = project;
        this.saved = sources();
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

    /**
     * Everything this workspace can do as capabilities: every refactoring in the library (defined ones included)
     * and the session operations. Built on each call, so it reflects the current library.
     */
    public CapabilityRegistry capabilities() {
        var registry = new CapabilityRegistry();
        for (var refactoring : refactorings.values()) {
            registry.register(Capability.of(refactoring.id(), refactoring.description(), refactoring.params(),
                    args -> json(invoke(refactoring.id(), args))));
        }
        var path = Param.of("path", "text");
        var line = Param.of("line", "text");
        registry.register(Capability.of("ls", "Source files", List.of(), args -> List.copyOf(files())))
                .register(Capability.of("show", "Source of a file", List.of(path), args -> source(text(args, "path"))))
                .register(Capability.of("diff", "Net diff of unsaved changes", List.of(), args -> diff()))
                .register(Capability.of("undo", "Reverts the last applied transaction", List.of(), args -> {
                    undo();
                    return "ok";
                }))
                .register(Capability.of("library", "Refactorings by level", List.of(), args -> library()))
                .register(Capability.of("define", "Registers refactorings written in .ref", List.of(Param.of("text", "text")),
                        args -> define(text(args, "text"))))
                .register(Capability.of("diagnostics", "Compiler errors", List.of(), args -> diagnostics().stream()
                        .map(d -> Map.of("code", d.code(), "path", d.path(), "line", d.line(), "message", d.message())).toList()))
                .register(Capability.of("fixes", "Quick fixes for a line", List.of(path, line),
                        args -> fixes(text(args, "path"), Integer.parseInt(text(args, "line")))))
                .register(Capability.of("fix", "Applies a quick fix", List.of(path, line, Param.of("title", "text")),
                        args -> json(fix(text(args, "path"), Integer.parseInt(text(args, "line")), text(args, "title")))))
                .register(Capability.of("why", "How the fixes for a line are deduced", List.of(path, line),
                        args -> why(text(args, "path"), Integer.parseInt(text(args, "line")))))
                .register(Capability.of("complete", "Completions at an offset of edited text",
                        List.of(path, Param.of("text", "text"), Param.of("offset", "text")),
                        args -> complete(text(args, "path"), text(args, "text"), Integer.parseInt(text(args, "offset")))))
                .register(Capability.of("preview", "A refactoring's diff, without keeping it",
                        List.of(Param.of("refactoring", "text"), Param.of("args", "text")), args -> {
                            var preview = preview(text(args, "refactoring"), argsOf(args));
                            var answer = new java.util.LinkedHashMap<String, Object>(json(preview.result()));
                            answer.put("diff", preview.diff());
                            return answer;
                        }))
                .register(Capability.of("expand", "A refactoring's primitives, without applying them",
                        List.of(Param.of("refactoring", "text"), Param.of("args", "text")),
                        args -> expand(text(args, "refactoring"), argsOf(args))));
        return registry;
    }

    private static String text(Map<String, ?> args, String name) {
        var value = args.get(name);
        if (value == null) throw new IllegalArgumentException("missing argument: " + name);
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> argsOf(Map<String, ?> args) {
        return args.get("args") instanceof Map<?, ?> nested ? (Map<String, ?>) nested : Map.of();
    }

    private static Map<String, Object> json(Result result) {
        var answer = new java.util.LinkedHashMap<String, Object>();
        answer.put("applied", result.applied());
        answer.put("violations", result.violations().stream().map(v -> v.code() + ": " + v.detail()).toList());
        answer.put("repairs", result.repairs());
        answer.put("steps", result.steps());
        return answer;
    }

    /** A refactoring's verified outcome and its diff; the program is left as it was. */
    public record Preview(Result result, String diff) {
    }

    public Preview preview(String refactoring, Map<String, ?> args) {
        var before = sources();
        var result = invoke(refactoring, args);
        var diff = diff(before, sources());
        if (result.applied()) undo();
        return new Preview(result, diff);
    }

    /** The step tree a refactoring would produce, without verifying or keeping anything. */
    public List<String> expand(String refactoring, Map<String, ?> args) {
        var tx = new Transaction(project);
        try {
            ctx(tx).call(refactoring, args);
        } catch (PrimitiveRejected rejected) {
            tx.discarded(rejected.code() + " " + rejected.getMessage());
        }
        var steps = tx.abort(null).steps();
        return steps;
    }

    /** Net diff of every unsaved change. */
    public String diff() {
        return diff(saved, sources());
    }

    /** Writes the changed files under {@code root}; nothing reaches disk before. */
    public void save(Path root) {
        var current = sources();
        try {
            for (var entry : current.entrySet()) {
                if (entry.getValue().equals(saved.get(entry.getKey()))) continue;
                var file = root.resolve(entry.getKey());
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, entry.getValue());
            }
            for (var path : saved.keySet()) {
                if (!current.containsKey(path)) java.nio.file.Files.deleteIfExists(root.resolve(path));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        saved = current;
        log.append(Map.of("event", "save", "root", root.toString()));
    }

    private Map<String, String> sources() {
        var sources = new java.util.TreeMap<String, String>();
        project.files().forEach(path -> sources.put(path, project.source(path)));
        return sources;
    }

    private static String diff(Map<String, String> before, Map<String, String> after) {
        var paths = new java.util.TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        var out = new StringBuilder();
        for (var path : paths) out.append(TextDiff.unified(path, before.getOrDefault(path, ""), after.getOrDefault(path, "")));
        return out.toString();
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
        var ctx = ctx(tx);
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

    /** What refactorings see inside {@code tx}. */
    private Ctx ctx(Transaction tx) {
        return new Ctx() {
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
    }

    private Refactoring refactoring(String id) {
        var refactoring = refactorings.get(id);
        if (refactoring == null) throw new IllegalArgumentException("unknown refactoring: " + id);
        return refactoring;
    }
}
