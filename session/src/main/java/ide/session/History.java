package ide.session;

import ide.core.Json;
import ide.core.TextDiff;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Every transaction applied, as a step to see again: what ran, what it was given, the task being done then, the places
 * of the code it took, and each file it changed as it was before and after (none before: it created it; none after:
 * it deleted it). An undone transaction is no step. Going on from a step brings the files it left back, and the steps
 * after it stay, discarded. Kept in a JSONL file, each line appended (none: kept only here).
 */
final class History {

    record Place(String path, int line, int endLine, String element) {
        Map<String, Object> shown() {
            var shown = new LinkedHashMap<String, Object>();
            shown.put("path", path);
            shown.put("line", line);
            shown.put("endLine", endLine);
            if (!element.isEmpty()) shown.put("element", element);
            return shown;
        }
    }

    record Change(String path, String before, String after) {
    }

    private static final class Step {
        int n;
        String what;
        Map<String, Object> args;
        String task;
        List<Place> where;
        List<Change> changes;
        boolean discarded;
    }

    private final Path file;
    private final List<Step> steps = new ArrayList<>(); // the ones not undone, in order
    private int numbered;

    History(Path file) {
        this.file = file;
        if (file != null && Files.exists(file)) read();
    }

    /** A transaction applied: the files that differ between {@code before} and {@code after} are what it changed. */
    void add(String what, Map<String, ?> args, String task, List<Place> where, Map<String, String> before, Map<String, String> after) {
        var changes = new ArrayList<Change>();
        var paths = new TreeSet<>(before.keySet());
        paths.addAll(after.keySet());
        for (var path : paths) {
            if (!Objects.equals(before.get(path), after.get(path))) changes.add(new Change(path, before.get(path), after.get(path)));
        }
        var step = new Step();
        step.n = ++numbered;
        step.what = what;
        step.args = new LinkedHashMap<>(args);
        step.task = task;
        step.where = List.copyOf(where);
        step.changes = List.copyOf(changes);
        steps.add(step);
        var line = new LinkedHashMap<String, Object>();
        line.put("step", step.n);
        line.put("what", what);
        line.put("args", step.args);
        line.put("task", task);
        line.put("where", where.stream().map(Place::shown).toList());
        line.put("changes", changes.stream().map(c -> {
            var shown = new LinkedHashMap<String, Object>();
            shown.put("path", c.path());
            shown.put("before", c.before());
            shown.put("after", c.after());
            return shown;
        }).toList());
        append(line);
    }

    /** The last transaction was undone: the last step that is not discarded is none (each transaction is a step). */
    void undone() {
        for (var i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).discarded) continue;
            append(Map.of("undo", steps.remove(i).n));
            return;
        }
    }

    /** The steps, those of the tasks {@code inTask} accepts. */
    List<Map<String, Object>> steps(java.util.function.Predicate<String> inTask) {
        return steps.stream().filter(s -> inTask.test(s.task)).map(History::summary).toList();
    }

    /** A step with what it changed: each file before and after, and the diff. */
    Map<String, Object> step(int n) {
        var step = find(n);
        var shown = summary(step);
        var also = new ArrayList<Map<String, Object>>();
        shown.put("changes", step.changes.stream().map(c -> {
            var change = new LinkedHashMap<String, Object>();
            change.put("path", c.path());
            change.put("status", c.before() == null ? "new" : c.after() == null ? "deleted" : "changed");
            change.put("before", c.before());
            change.put("after", c.after());
            change.put("diff", TextDiff.unified(c.path(), Objects.requireNonNullElse(c.before(), ""), Objects.requireNonNullElse(c.after(), "")));
            var zone = new java.util.HashSet<Integer>();
            step.where.stream().filter(w -> w.path().equals(c.path())).forEach(w -> {
                for (var line = w.line(); line <= w.endLine(); line++) zone.add(line);
            });
            var compared = SideBySide.of(c.before(), c.after(), zone);
            change.put("rows", compared.rows().stream().map(SideBySide.Row::shown).toList());
            // what changed away from what the step takes: in a file it names nothing of, all of it
            var outside = zone.isEmpty() && !step.where.isEmpty() ? List.of(new SideBySide.Also(0, 1, c.before() == null ? "new" : "changed"))
                    : compared.also();
            outside.forEach(a -> {
                var entry = new LinkedHashMap<String, Object>();
                entry.put("path", c.path());
                entry.put("row", a.row());
                entry.put("line", a.line());
                entry.put("text", a.text().strip());
                also.add(entry);
            });
            return change;
        }).toList());
        shown.put("also", also);
        return shown;
    }

    /**
     * What each file has to be for the files to be as step {@code n} left them, given what they are now: each file a
     * later step changed, as it was before the first of them (none: it did not exist). Refused when a file is not
     * what the last step that changed it left: it changed since, otherwise.
     */
    Map<String, String> back(int n, Map<String, String> now) {
        find(n);
        var restored = new TreeMap<String, String>();
        var last = new TreeMap<String, String>();
        var later = steps.stream().filter(s -> s.n > n && !s.discarded).toList();
        for (var step : later) {
            for (var change : step.changes) {
                if (!restored.containsKey(change.path())) restored.put(change.path(), change.before());
                last.put(change.path(), change.after());
            }
        }
        for (var path : last.keySet()) {
            if (!Objects.equals(last.get(path), now.get(path))) {
                throw new IllegalStateException(path + " changed since step " + later.stream()
                        .filter(s -> s.changes.stream().anyMatch(c -> c.path().equals(path))).reduce((a, b) -> b).orElseThrow().n
                        + " left it; the steps after " + n + " cannot be taken back");
            }
        }
        return restored;
    }

    /** The steps after {@code n} are discarded: the files are as it left them. */
    void discardAfter(int n) {
        var discarded = steps.stream().filter(s -> s.n > n && !s.discarded).toList();
        discarded.forEach(s -> s.discarded = true);
        append(Map.of("branch", n, "discarded", discarded.stream().map(s -> s.n).toList()));
    }

    private Step find(int n) {
        return steps.stream().filter(s -> s.n == n).findFirst().orElseThrow(() -> new IllegalArgumentException("no step " + n));
    }

    private static Map<String, Object> summary(Step step) {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("n", step.n);
        shown.put("what", step.what);
        shown.put("args", step.args);
        shown.put("task", step.task);
        shown.put("where", step.where.stream().map(Place::shown).toList());
        shown.put("files", step.changes.stream().map(c -> c.before() == null ? c.path() + " (new)" : c.after() == null ? c.path() + " (deleted)" : c.path()).toList());
        shown.put("discarded", step.discarded);
        return shown;
    }

    private void append(Map<String, ?> line) {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot keep the history in " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void read() {
        try {
            for (var text : Files.readAllLines(file)) {
                if (text.isBlank()) continue;
                var line = (Map<String, Object>) Json.read(text);
                if (line.containsKey("undo")) {
                    var n = ((Number) line.get("undo")).intValue();
                    steps.removeIf(s -> s.n == n);
                } else if (line.containsKey("branch")) {
                    for (var n : (List<Number>) line.get("discarded")) {
                        steps.stream().filter(s -> s.n == n.intValue()).forEach(s -> s.discarded = true);
                    }
                } else {
                    var step = new Step();
                    step.n = ((Number) line.get("step")).intValue();
                    step.what = String.valueOf(line.get("what"));
                    step.args = (Map<String, Object>) line.get("args");
                    step.task = String.valueOf(line.get("task"));
                    step.where = ((List<Map<String, Object>>) line.get("where")).stream().map(w -> new Place(String.valueOf(w.get("path")),
                            ((Number) w.get("line")).intValue(), ((Number) w.get("endLine")).intValue(),
                            String.valueOf(w.getOrDefault("element", "")))).toList();
                    step.changes = ((List<Map<String, Object>>) line.get("changes")).stream().map(c -> new Change(String.valueOf(c.get("path")),
                            (String) c.get("before"), (String) c.get("after"))).toList();
                    steps.add(step);
                    numbered = Math.max(numbered, step.n);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the history in " + file, e);
        }
    }
}
