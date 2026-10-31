package ide.session;

import ide.core.Capability;
import ide.core.CapabilityRegistry;
import ide.core.EventLog;
import ide.core.TextDiff;
import ide.engine.Plan;
import ide.engine.QuickFix;
import ide.knowledge.Knowledge;
import ide.engine.Repairer;
import ide.engine.Result;
import ide.engine.Transaction;
import ide.engine.Violation;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;
import ide.java.JavaProjection;
import ide.refactoring.Library;
import ide.refactoring.Run;
import ide.core.Param;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Entry point: invokes refactorings as transactions over a Java project. */
public final class Workspace {

    private final JavaProjection project;
    private final LanguageRules rules;
    private final Library library = Library.standard();
    private final Knowledge knowledge = Knowledge.standard(library);
    private final Repairer repairer;
    private final List<QuickFix> quickFixes = List.of(knowledge);
    private final Navigation navigation;
    private final Completion completion;

    private final java.util.Deque<Projection.Snapshot> undo = new java.util.ArrayDeque<>();
    private EventLog log = EventLog.NONE;
    private ide.engine.Trace lastSearch = new ide.engine.Trace();

    /** The sources as they are on disk (as loaded, or as last saved). */
    private Map<String, String> saved;

    private Workspace(JavaProjection project) {
        this.project = project;
        this.rules = project.rules();
        this.repairer = new Repairer(List.of(knowledge));
        this.navigation = new Navigation(project);
        this.completion = new Completion(project);
        this.saved = sources();
    }

    /** Registers the refactorings written in a {@code .ref} text; returns their ids. */
    public List<String> define(String scripts) {
        var defined = library.define(scripts);
        log.append(Map.of("event", "define", "refactorings", defined));
        return defined;
    }

    /** {@code "<level> <id>"} for every refactoring in the library. */
    public List<String> library() {
        return library.listing();
    }

    public static Workspace of(Map<String, String> sources) {
        return new Workspace(JavaProjection.of(sources));
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
        for (var refactoring : library.all()) {
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
                        args -> why(text(args, "path"), Integer.parseInt(text(args, "line"))).lines()))
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
                .register(Capability.of("why-repair", "How the last transaction's repairs were searched", List.of(),
                        args -> whyRepair().lines()))
                .register(Capability.of("callers", "Who calls a method (polymorphically)", List.of(Param.of("method", "element", "METHOD")),
                        args -> callers(text(args, "method"))))
                .register(Capability.of("hierarchy", "Supertypes and subtypes of a type", List.of(Param.of("type", "element", "CLASS", "INTERFACE", "ENUM")),
                        args -> hierarchy(text(args, "type"))))
                .register(Capability.of("references", "Every use of an element", List.of(Param.of("element", "element")),
                        args -> references(text(args, "element"))))
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
        answer.put("guarantee", result.guarantee());
        answer.put("obligations", result.obligations());
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
        var tx = new Transaction(project, repairer);
        try {
            Run.in(tx, library).call(refactoring, args);
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

    public Map<String, String> sources() {
        return project.sources();
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

    /** Applies a manual edit of a file's text as its own (undoable, logged) transaction. */
    public Result edit(String path, String text) {
        var tx = new Transaction(project, repairer);
        Result result;
        try {
            tx.edit(path, text);
            result = tx.keep();
        } catch (PrimitiveRejected rejected) {
            result = tx.abort(new Violation(rejected.code(), null, null, rejected.getMessage()));
        }
        return record(tx, Map.of("event", "edit", "path", path), result);
    }

    /** Reverts the last applied transaction. */
    public void undo() {
        if (undo.isEmpty()) throw new IllegalStateException("nothing to undo");
        project.restore(undo.pop());
        log.append(Map.of("event", "undo"));
    }

    public java.util.Set<String> files() {
        return project.files();
    }

    /** Runs a refactoring naively in a transaction, then verifies and repairs; rolls back if violations remain. */
    public Result invoke(String refactoring, Map<String, ?> args) {
        var tx = new Transaction(project, repairer);
        Result result;
        try {
            Run.in(tx, library).call(refactoring, args);
            result = tx.commit();
        } catch (PrimitiveRejected rejected) {
            result = tx.abort(new Violation(rejected.code(), null, null, rejected.getMessage()));
        }
        return record(tx, Map.of("event", "transaction", "refactoring", refactoring, "args", args), result);
    }

    /** Logs a finished transaction and makes it undoable if it applied. */
    private Result record(Transaction tx, Map<String, ?> event, Result result) {
        if (result.applied()) undo.push(tx.before());
        lastSearch = result.search();
        var entry = new java.util.LinkedHashMap<String, Object>(event);
        entry.put("applied", result.applied());
        entry.put("violations", result.violations().stream().map(v -> v.code() + ": " + v.detail()).toList());
        entry.put("repairs", result.repairs());
        entry.put("steps", result.steps());
        log.append(entry);
        return result;
    }

    /** Completions at {@code offset} of the editor text of {@code path}. */
    public List<String> complete(String path, String text, int offset) {
        return completion.at(path, text, offset);
    }

    /** The model as sorted text: {@code KIND qualified} per element, {@code site -> target (mode)} per reference. */
    public List<String> model() {
        return navigation.model();
    }

    /** Members whose code calls the method, or a method it overrides (such a call may dispatch to it). */
    public List<String> callers(String method) {
        return navigation.callers(method);
    }

    /** Supertypes (nearest first), the type marked with {@code >}, then subtypes indented by depth. */
    public List<String> hierarchy(String type) {
        return navigation.hierarchy(type);
    }

    /** The site of every use of an element, in reference order. */
    public List<String> references(String element) {
        return navigation.references(element);
    }

    public List<Diagnostic> diagnostics() {
        return project.diagnostics();
    }

    /** Titles of the fixes offered for the diagnostics on a line. */
    public List<String> fixes(String path, int line) {
        return diagnosticsAt(path, line).stream()
                .flatMap(d -> quickFixes.stream().flatMap(f -> f.plans(d, project).stream()))
                .map(Plan::title).distinct().toList();
    }

    /** The questions a fix asks: {@code name: question [default]}. */
    public List<String> asks(String path, int line, String title) {
        return plan(path, line, title).getValue().asks().stream()
                .map(a -> a.name() + ": " + a.question() + " [" + a.defaultAnswer() + "]").toList();
    }

    private Map.Entry<Diagnostic, Plan> plan(String path, int line, String title) {
        for (var diagnostic : diagnosticsAt(path, line)) {
            for (var quickFix : quickFixes) {
                for (var plan : quickFix.plans(diagnostic, project)) {
                    if (plan.title().equals(title)) return Map.entry(diagnostic, plan);
                }
            }
        }
        throw new IllegalArgumentException("no fix '" + title + "' at " + path + ":" + line);
    }

    public Result fix(String path, int line, String title) {
        return fix(path, line, title, Map.of());
    }

    /** Applies a fix (its questions answered, or defaulted) as a transaction that must make its diagnostic disappear. */
    public Result fix(String path, int line, String title, Map<String, String> answers) {
        var found = plan(path, line, title);
        var diagnostic = found.getKey();
        var tx = new Transaction(project, repairer);
        tx.enter("fix " + title);
        found.getValue().apply().accept(tx, answers);
        tx.exit();
        var event = Map.of("event", "fix", "path", path, "line", line, "title", title);
        if (project.diagnostics().contains(diagnostic)) {
            return record(tx, event, tx.abort(new Violation("fix.ineffective", null, null, title + " left " + diagnostic)));
        }
        return record(tx, event, tx.commit());
    }

    /** How the repairer searched in the last transaction: each violation, and each plan kept or undone. */
    public ide.engine.Trace whyRepair() {
        return lastSearch;
    }

    /** How fixes for the diagnostics on a line are deduced: each diagnostic, its goals, and the effects tried. */
    public ide.engine.Trace why(String path, int line) {
        var trace = new ide.engine.Trace();
        for (var d : diagnosticsAt(path, line)) {
            trace.add(ide.engine.Trace.Kind.NOTE, d.code() + " " + d.symbol());
            trace.append(knowledge.search(d, project).trace());
        }
        return trace;
    }

    private List<Diagnostic> diagnosticsAt(String path, int line) {
        return project.diagnostics().stream().filter(d -> d.path().equals(path) && d.line() == line).toList();
    }
}
