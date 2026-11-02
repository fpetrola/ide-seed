package ide.session;

import ide.core.EventLog;
import ide.core.TextDiff;
import ide.engine.Repairer;
import ide.engine.Result;
import ide.engine.Trace;
import ide.engine.Transaction;
import ide.engine.Violation;
import ide.java.JavaProjection;
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;
import ide.refactoring.Library;
import ide.refactoring.Run;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The user's operations on a project, each one a transaction: run a refactoring, edit a file's text, apply a fix.
 * It keeps what can be undone, the log of every event, and the sources as they were last saved.
 */
public final class Session {

    /** A refactoring's verified outcome and its diff; the program is left as it was. */
    public record Preview(Result result, String diff) {
    }

    /** The folders builds write to. */
    private static final Set<String> BUILT = Set.of("target", "build", "out");

    private final JavaProjection project;
    private final Library library;
    private final Repairer repairer;
    private final Deque<Projection.Snapshot> undo = new ArrayDeque<>();
    private EventLog log = EventLog.NONE;
    private Trace lastSearch = new Trace();
    private Map<String, String> saved;

    public Session(JavaProjection project, Library library, Repairer repairer) {
        this.project = project;
        this.library = library;
        this.repairer = repairer;
        this.saved = project.sources();
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

    /** What a refactoring would do, verified, as a diff; nothing is kept. */
    public Preview preview(String refactoring, Map<String, ?> args) {
        var before = project.sources();
        var result = invoke(refactoring, args);
        var diff = diff(before, project.sources());
        if (result.applied()) undo();
        return new Preview(result, diff);
    }

    /** The step tree a refactoring would produce, without verifying or keeping anything. */
    /** The elements a refactoring's parameter may take, given the others; nothing if any of its kinds will do. */
    public Optional<List<String>> options(String refactoring, String param, Map<String, ?> args) {
        return library.get(refactoring).options(Run.on(project, library), new ide.refactoring.Args(args), param);
    }

    /** What a refactoring suggests for the parameters not given, from those given: its default names. */
    public Map<String, String> suggest(String refactoring, Map<String, ?> args) {
        return library.get(refactoring).suggestions(Run.on(project, library), new ide.refactoring.Args(args));
    }

    public List<String> expand(String refactoring, Map<String, ?> args) {
        var tx = new Transaction(project, repairer);
        try {
            Run.in(tx, library).call(refactoring, args);
        } catch (PrimitiveRejected rejected) {
            tx.discarded(rejected.code() + " " + rejected.getMessage());
        }
        return tx.abort(null).steps();
    }

    /** A manual edit of a file's text, as its own transaction: the user's text is the authority. */
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

    /** Applies a fix, its questions answered or defaulted, as a transaction that must make its diagnostic disappear. */
    public Result fix(Fix fix, Map<String, String> answers) {
        var tx = new Transaction(project, repairer);
        var title = fix.plan().title();
        tx.enter("fix " + title);
        fix.plan().apply().accept(tx, answers);
        tx.exit();
        var diagnostic = fix.diagnostic();
        var event = Map.of("event", "fix", "path", diagnostic.path(), "line", diagnostic.line(), "title", title);
        if (project.diagnostics().contains(diagnostic)) {
            return record(tx, event, tx.abort(new Violation("fix.ineffective", null, null, title + " left " + diagnostic)));
        }
        return record(tx, event, tx.commit());
    }

    /** Reverts the last applied transaction. */
    public void undo() {
        if (undo.isEmpty()) throw new IllegalStateException("nothing to undo");
        project.restore(undo.pop());
        log.append(Map.of("event", "undo"));
    }

    /** Adds the refactorings written in a {@code .ref} text to the library; answers their ids. */
    public List<String> define(String scripts) {
        var defined = library.define(scripts);
        log.append(Map.of("event", "define", "refactorings", defined));
        return defined;
    }

    /** Net diff of every unsaved change. */
    public String diff() {
        return diff(saved, project.sources());
    }

    /**
     * The Java sources under {@code root}, by path from it: what {@link #save} writes back. What builds and tools write
     * is not a source: folders named target, build or out, and hidden ones.
     */
    public static Map<String, String> sourcesIn(Path root) {
        var sources = new TreeMap<String, String>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
                    var name = folder.getFileName() == null ? "" : folder.getFileName().toString();
                    return !folder.equals(root) && (BUILT.contains(name) || name.startsWith("."))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (file.toString().endsWith(".java")) {
                        sources.put(root.relativize(file).toString().replace(File.separatorChar, '/'), Files.readString(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }

    /** Writes the changed files under {@code root}; nothing reaches disk before. */
    public void save(Path root) {
        var current = project.sources();
        try {
            for (var entry : current.entrySet()) {
                if (entry.getValue().equals(saved.get(entry.getKey()))) continue;
                var file = root.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
            }
            for (var path : saved.keySet()) {
                if (!current.containsKey(path)) Files.deleteIfExists(root.resolve(path));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        saved = current;
        log.append(Map.of("event", "save", "root", root.toString()));
    }

    /**
     * What the folder's files say that is not what was last read or saved there.
     * {@code taken}: changed or new on disk only, now edits by hand; {@code conflicts}: changed on disk and in the IDE
     * too, left as they are; {@code refused}: an edit the program did not take; {@code deleted}: gone from disk, which
     * is not followed (the IDE still has them, and saving writes them back).
     */
    public record Sync(List<String> taken, List<String> conflicts, List<String> refused, List<String> deleted) {
    }

    /** Takes what changed on disk under {@code root} since it was read or saved, each file as an edit by hand. */
    public Sync sync(Path root) {
        var disk = sourcesIn(root);
        var current = project.sources();
        var taken = new java.util.ArrayList<String>();
        var conflicts = new java.util.ArrayList<String>();
        var refused = new java.util.ArrayList<String>();
        var known = new TreeMap<>(saved);
        for (var file : disk.entrySet()) {
            var path = file.getKey();
            var text = file.getValue();
            if (text.equals(known.get(path))) continue; // nothing changed on disk
            if (text.equals(current.get(path))) { // the disk caught up with the IDE
                known.put(path, text);
                continue;
            }
            if (!java.util.Objects.equals(current.get(path), known.get(path))) {
                conflicts.add(path);
                continue;
            }
            if (edit(path, text).applied()) {
                known.put(path, text);
                taken.add(path);
            } else {
                refused.add(path);
            }
        }
        var deleted = known.keySet().stream().filter(path -> !disk.containsKey(path)).toList();
        saved = known;
        if (!taken.isEmpty() || !conflicts.isEmpty() || !refused.isEmpty() || !deleted.isEmpty()) {
            log.append(Map.of("event", "sync", "root", root.toString(), "taken", taken, "conflicts", conflicts,
                    "refused", refused, "deleted", deleted));
        }
        return new Sync(List.copyOf(taken), List.copyOf(conflicts), List.copyOf(refused), deleted);
    }

    /** That the project in {@code root} was opened, with how many libraries and what went wrong getting them. */
    public void opened(Path root, int libraries, String problem) {
        var event = new LinkedHashMap<String, Object>();
        event.put("event", "open");
        event.put("root", root.toString());
        event.put("libraries", libraries);
        if (problem != null) event.put("problem", problem);
        log.append(event);
    }

    /** Appends every event to a JSONL file from now on. */
    public void logTo(Path file) {
        log = EventLog.to(file);
    }

    /** How the repairer searched in the last transaction: each violation, and each plan kept or undone. */
    public Trace whyRepair() {
        return lastSearch;
    }

    /** A result as the log and the adapters write it. */
    public static Map<String, Object> report(Result result) {
        var report = new LinkedHashMap<String, Object>();
        report.put("applied", result.applied());
        report.put("violations", result.violations().stream().map(v -> v.code() + ": " + v.detail()).toList());
        report.put("repairs", result.repairs());
        report.put("steps", result.steps());
        report.put("guarantee", result.guarantee());
        report.put("obligations", result.obligations());
        return report;
    }

    /** Logs a finished transaction and makes it undoable if it applied. */
    private Result record(Transaction tx, Map<String, ?> event, Result result) {
        if (result.applied()) undo.push(tx.before());
        lastSearch = result.search();
        var entry = new LinkedHashMap<String, Object>(event);
        entry.putAll(report(result));
        log.append(entry);
        return result;
    }

    private static String diff(Map<String, String> before, Map<String, String> after) {
        var paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        var out = new StringBuilder();
        for (var path : paths) out.append(TextDiff.unified(path, before.getOrDefault(path, ""), after.getOrDefault(path, "")));
        return out.toString();
    }
}
