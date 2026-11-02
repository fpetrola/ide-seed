package ide.session;

import ide.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The plans a person and an agent follow while they work on the IDE (not the engine's repair Plan). Several, one of
 * them active; each a tree of tasks: the top ones are its lines of action, the others their subtasks, in order. A task
 * may wait for others, links places of the code, and keeps a timeline of what was said and done about it, and by whom.
 * Ids are stable ({@code p1}, {@code t7}), so replanning (adding, moving, dropping) keeps who each task is. What the
 * person says about a plan or a task (pause, resume, comment, replan, switch) changes it at once and waits to be told
 * to the agent, once. Knows nothing of programs. Kept in a file with the project, if there is one; each change an event.
 */
public final class Plans {

    enum PlanState {
        ACTIVE, PAUSED, DONE, DROPPED
    }

    enum State {
        TODO, DOING, BLOCKED, PAUSED, DONE, DROPPED;

        boolean finished() {
            return this == DONE || this == DROPPED;
        }
    }

    /** A place of the code a task is about: lines of a file, the element they declare, if any, and why. */
    record Link(String path, int line, int endLine, String element, String note) {
    }

    /** Something said or done about a task: when, by whom (agent or person), what kind, and what. */
    record Entry(String at, String who, String kind, String text) {
    }

    /** What the person said, as it is told to the agent; told already, or waiting. */
    record Steer(int n, String message, boolean told) {
    }

    private static final class Plan {
        String id;
        String title;
        String description;
        PlanState state;
        final List<String> tasks = new ArrayList<>(); // depth first: each task right after its parent's earlier ones
    }

    private static final class Task {
        String id;
        String plan;
        String title;
        String description = "";
        State state = State.TODO;
        String parent = "";
        List<String> after = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        final List<Entry> timeline = new ArrayList<>();
    }

    private final Path file;
    private final Consumer<Map<String, Object>> events;
    private final Clock clock;
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<Steer> steering = new ArrayList<>();
    private String active = "";
    private int planned;
    private int numbered;

    /** Plans kept in {@code file} (none: kept nowhere), read from it if it is there, telling each change to {@code events}. */
    Plans(Path file, Consumer<Map<String, Object>> events, Clock clock) {
        this.file = file;
        this.events = events;
        this.clock = clock;
        if (file != null && Files.exists(file)) read();
    }

    // ---- what the agent (or the person) does

    /**
     * A new plan, the active one from now on (the one that was is paused): its title, what it is for, and its tasks,
     * one per line, each indented under the line it is part of.
     */
    Map<String, Object> create(String title, String description, String outline) {
        var plan = new Plan();
        plan.id = "p" + ++planned;
        plan.title = title.strip();
        plan.description = description.strip();
        plan.state = PlanState.ACTIVE;
        pauseActive();
        plans.put(plan.id, plan);
        active = plan.id;
        var parents = new ArrayList<String>(); // the task at each depth so far
        var indents = new ArrayList<Integer>();
        for (var line : outline.lines().toList()) {
            if (line.isBlank()) continue;
            var indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.getLast() >= indent) {
                indents.removeLast();
                parents.removeLast();
            }
            var task = newTask(plan, line.strip(), parents.isEmpty() ? "" : parents.getLast());
            plan.tasks.add(task.id);
            indents.add(indent);
            parents.add(task.id);
        }
        changed(Map.of("event", "plan", "plan", plan.id, "title", plan.title, "tasks", plan.tasks.size()));
        return shown(plan);
    }

    /** The plan that goes on from now: the active one is paused. */
    void activate(String id) {
        var plan = plan(id);
        if (plan.id.equals(active)) return;
        pauseActive();
        plan.state = PlanState.ACTIVE;
        active = plan.id;
        changed(Map.of("event", "plan", "plan", plan.id, "state", "active"));
    }

    void state(String id, PlanState state) {
        var plan = plan(id);
        plan.state = state;
        if (state == PlanState.ACTIVE && !plan.id.equals(active)) activate(plan.id);
        changed(Map.of("event", "plan", "plan", plan.id, "state", said(state)));
    }

    /** A task added to a plan (the active one, if none is said), at the end of its parent's, or of the plan. */
    Map<String, Object> add(Optional<String> planId, String title, String description, Optional<String> parent, List<String> after, String who) {
        var plan = parent.map(p -> plans.get(task(p).plan)).orElseGet(() -> plan(planId.orElse(active)));
        var task = newTask(plan, title.strip(), parent.orElse(""));
        task.description = description.strip();
        place(plan, task);
        wait(task, after);
        task.timeline.add(entry(who, "created", task.title));
        changed(Map.of("event", "task", "task", task.id, "plan", plan.id, "title", task.title));
        return shown(task);
    }

    /** Changes what is given of a task; a note goes to its timeline. */
    Map<String, Object> update(String id, Map<String, Optional<String>> changes, String who) {
        var task = task(id);
        var event = new LinkedHashMap<String, Object>(Map.of("event", "task", "task", id));
        changes.getOrDefault("title", Optional.empty()).ifPresent(title -> {
            task.title = title.strip();
            event.put("title", task.title);
        });
        changes.getOrDefault("description", Optional.empty()).ifPresent(description -> task.description = description.strip());
        changes.getOrDefault("parent", Optional.empty()).ifPresent(parent -> {
            var to = parent.equals("none") ? "" : parent;
            if (!to.isEmpty()) {
                if (!task(to).plan.equals(task.plan)) throw new IllegalArgumentException(to + " is in another plan than " + id);
                if (to.equals(id) || descendants(id).contains(to)) {
                    throw new IllegalArgumentException(id + " cannot go under " + to + ": " + to + " is part of " + id);
                }
            }
            var plan = plans.get(task.plan);
            var moved = new ArrayList<String>(List.of(id));
            moved.addAll(descendants(id));
            plan.tasks.removeAll(moved);
            task.parent = to;
            var at = to.isEmpty() ? plan.tasks.size() : plan.tasks.indexOf(last(to)) + 1;
            plan.tasks.addAll(at, moved);
            event.put("parent", to);
        });
        changes.getOrDefault("after", Optional.empty()).ifPresent(after ->
                wait(task, after.isBlank() || after.equals("none") ? List.of() : List.of(after.split("\\s*,\\s*"))));
        changes.getOrDefault("state", Optional.empty()).ifPresent(state -> {
            task.state = State.valueOf(state.toUpperCase(Locale.ROOT));
            task.timeline.add(entry(who, "state", said(task.state)));
            event.put("state", said(task.state));
        });
        changes.getOrDefault("note", Optional.empty()).filter(note -> !note.isBlank())
                .ifPresent(note -> task.timeline.add(entry(who, "note", note.strip())));
        changed(event);
        return shown(task);
    }

    Map<String, Object> link(String id, Link link) {
        var task = task(id);
        task.links.add(link);
        changed(Map.of("event", "task", "task", id, "link", link.path() + ":" + link.line()));
        return shown(task);
    }

    // ---- what the person says

    /**
     * What the person says about a plan or a task: pause, resume, comment, replan or switch (to a plan). It changes
     * what it says at once and answers the message for the agent, which waits to be told unless the window told it.
     */
    Steer steer(String kind, Optional<String> planId, Optional<String> taskId, String text, boolean told) {
        var task = taskId.map(this::task);
        var plan = plan(task.map(t -> t.plan).orElse(planId.orElse(active)));
        var what = task.map(t -> "task " + t.id + " \"" + t.title + "\" (plan " + t.plan + ")")
                .orElse("plan " + plan.id + " \"" + plan.title + "\"");
        var said = text.isBlank() ? "" : ": " + text.strip();
        var message = switch (kind) {
            case "pause" -> {
                if (task.isPresent()) task.get().state = State.PAUSED;
                else plan.state = PlanState.PAUSED;
                yield "The person paused " + what + said + (task.isPresent()
                        ? ". Stop working on it; go on with other tasks or wait."
                        : ". Finish the step you are in and wait for the person.");
            }
            case "resume" -> {
                if (task.isPresent()) task.get().state = State.TODO;
                else if (plan.id.equals(active)) plan.state = PlanState.ACTIVE;
                else activate(plan.id);
                yield "The person resumed " + what + said + ". Go on with it.";
            }
            case "comment" -> "The person comments on " + what + said;
            case "replan" -> "The person asks to replan " + what + said
                    + ". Change the tasks with task-add and task-update, then tell the person what changed.";
            case "switch" -> {
                var was = active;
                activate(plan.id);
                yield "The person switched to " + what + (was.isEmpty() || was.equals(plan.id) ? "" : "; plan " + was + " waits, paused")
                        + said + ". Go on with it.";
            }
            default -> throw new IllegalArgumentException("say pause, resume, comment, replan or switch, not " + kind);
        };
        task.ifPresent(t -> t.timeline.add(entry("person", kind, text.isBlank() ? kind : text.strip())));
        var steer = new Steer(steering.size() + 1, message, told);
        steering.add(steer);
        changed(Map.of("event", "steer", "kind", kind, "about", task.map(t -> t.id).orElse(plan.id), "text", text.strip()));
        return steer;
    }

    /** What the person said that the agent was not told yet, now told. */
    List<Steer> take() {
        var waiting = steering.stream().filter(steer -> !steer.told()).toList();
        if (waiting.isEmpty()) return waiting;
        steering.replaceAll(steer -> new Steer(steer.n(), steer.message(), true));
        save();
        return waiting;
    }

    // ---- what is shown

    /** Every plan, in the order they were made, with its state and how many of its tasks are finished. */
    List<Map<String, Object>> list() {
        return plans.values().stream().map(plan -> {
            var shown = new LinkedHashMap<String, Object>();
            shown.put("id", plan.id);
            shown.put("title", plan.title);
            shown.put("state", said(plan.state));
            shown.put("active", plan.id.equals(active));
            shown.put("done", done(plan.tasks));
            shown.put("of", plan.tasks.size());
            return (Map<String, Object>) shown;
        }).toList();
    }

    /** A plan (the active one, if none is said) with its tasks depth first; nothing (empty id) if there is none. */
    Map<String, Object> plan(Optional<String> id) {
        if (id.isEmpty() && active.isEmpty()) {
            var none = new LinkedHashMap<String, Object>();
            none.put("id", "");
            none.put("title", "");
            none.put("tasks", List.of());
            none.put("done", 0);
            none.put("of", 0);
            return none;
        }
        return shown(plan(id.orElse(active)));
    }

    /** A task with everything it has: its fields, what it waits for, its links and its timeline. */
    Map<String, Object> task(Optional<String> id) {
        return shown(task(id.orElseThrow(() -> new IllegalArgumentException("say which task"))));
    }

    // ---- inside

    private Plan plan(String id) {
        var plan = plans.get(id);
        if (plan == null) throw new IllegalArgumentException(id.isEmpty() ? "there is no plan: make one with plan-new" : "no plan " + id);
        return plan;
    }

    private Task task(String id) {
        var task = tasks.get(id);
        if (task == null) throw new IllegalArgumentException("no task " + id);
        return task;
    }

    private Task newTask(Plan plan, String title, String parent) {
        var task = new Task();
        task.id = "t" + ++numbered;
        task.plan = plan.id;
        task.title = title;
        task.parent = parent;
        tasks.put(task.id, task);
        return task;
    }

    private void place(Plan plan, Task task) {
        plan.tasks.add(task.parent.isEmpty() ? plan.tasks.size() : plan.tasks.indexOf(last(task.parent)) + 1, task.id);
    }

    /** The last task of a subtree: where what goes at its end goes after. */
    private String last(String id) {
        var below = descendants(id);
        return below.isEmpty() ? id : below.getLast();
    }

    private List<String> descendants(String id) {
        var found = new ArrayList<String>();
        for (var other : plans.get(task(id).plan).tasks) {
            var task = tasks.get(other);
            if (task.parent.equals(id) || found.contains(task.parent)) found.add(other);
        }
        return found;
    }

    private void wait(Task task, List<String> after) {
        for (var other : after) {
            if (!task(other).plan.equals(task.plan)) throw new IllegalArgumentException(other + " is in another plan than " + task.id);
            if (other.equals(task.id) || waitsFor(other, task.id)) {
                throw new IllegalArgumentException(task.id + " cannot wait for " + other + ": " + other + " waits for " + task.id);
            }
        }
        task.after = new ArrayList<>(after);
    }

    private boolean waitsFor(String id, String other) {
        var seen = new LinkedHashSet<String>();
        var next = new ArrayList<>(List.of(id));
        while (!next.isEmpty()) {
            var at = next.removeLast();
            if (!seen.add(at)) continue;
            if (at.equals(other)) return true;
            next.addAll(tasks.get(at).after);
        }
        return false;
    }

    private void pauseActive() {
        if (!active.isEmpty() && plans.get(active).state == PlanState.ACTIVE) plans.get(active).state = PlanState.PAUSED;
    }

    private long done(List<String> ids) {
        return ids.stream().filter(id -> tasks.get(id).state.finished()).count();
    }

    private Entry entry(String who, String kind, String text) {
        return new Entry(clock.instant().toString(), who, kind, text);
    }

    private int depth(Task task) {
        var depth = 0;
        for (var at = task; !at.parent.isEmpty(); at = tasks.get(at.parent)) depth++;
        return depth;
    }

    private Map<String, Object> shown(Plan plan) {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("id", plan.id);
        shown.put("title", plan.title);
        shown.put("description", plan.description);
        shown.put("state", said(plan.state));
        shown.put("active", plan.id.equals(active));
        shown.put("tasks", plan.tasks.stream().map(id -> {
            var task = tasks.get(id);
            var line = new LinkedHashMap<String, Object>();
            line.put("id", id);
            line.put("title", task.title);
            line.put("state", said(task.state));
            line.put("parent", task.parent);
            line.put("depth", depth(task));
            var below = descendants(id);
            line.put("done", done(below));
            line.put("of", below.size());
            line.put("waiting", waiting(task));
            return line;
        }).toList());
        shown.put("done", done(plan.tasks));
        shown.put("of", plan.tasks.size());
        return shown;
    }

    private List<String> waiting(Task task) {
        return task.after.stream().filter(id -> !tasks.get(id).state.finished()).toList();
    }

    private Map<String, Object> shown(Task task) {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("id", task.id);
        shown.put("plan", task.plan);
        shown.put("title", task.title);
        shown.put("description", task.description);
        shown.put("state", said(task.state));
        shown.put("parent", task.parent);
        shown.put("subtasks", tasks.values().stream().filter(t -> t.parent.equals(task.id)).map(t -> t.id).toList());
        shown.put("after", task.after);
        shown.put("waiting", waiting(task));
        shown.put("links", task.links.stream().map(link -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("path", link.path());
            entry.put("line", link.line());
            entry.put("endLine", link.endLine());
            entry.put("element", link.element());
            entry.put("note", link.note());
            return entry;
        }).toList());
        shown.put("timeline", task.timeline.stream().map(entry -> {
            var said = new LinkedHashMap<String, Object>();
            said.put("at", entry.at());
            said.put("who", entry.who());
            said.put("kind", entry.kind());
            said.put("text", entry.text());
            return said;
        }).toList());
        return shown;
    }

    private static String said(Enum<?> state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private void changed(Map<String, Object> event) {
        save();
        events.accept(event);
    }

    // ---- kept

    private void save() {
        if (file == null) return;
        var kept = new LinkedHashMap<String, Object>();
        kept.put("active", active);
        kept.put("planned", planned);
        kept.put("numbered", numbered);
        kept.put("plans", plans.values().stream().map(plan -> {
            var shown = new LinkedHashMap<String, Object>();
            shown.put("id", plan.id);
            shown.put("title", plan.title);
            shown.put("description", plan.description);
            shown.put("state", said(plan.state));
            shown.put("tasks", plan.tasks.stream().map(id -> shown(tasks.get(id))).toList());
            return shown;
        }).toList());
        kept.put("steering", steering.stream().map(steer -> Map.of("n", steer.n(), "message", steer.message(), "told", steer.told())).toList());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(kept));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot keep the plans in " + file, e);
        }
    }

    private void read() {
        try {
            var kept = (Map<?, ?>) Json.read(Files.readString(file));
            active = String.valueOf(kept.get("active"));
            planned = ((Number) kept.get("planned")).intValue();
            numbered = ((Number) kept.get("numbered")).intValue();
            for (var p : (List<?>) kept.get("plans")) {
                var entry = (Map<?, ?>) p;
                var plan = new Plan();
                plan.id = String.valueOf(entry.get("id"));
                plan.title = String.valueOf(entry.get("title"));
                plan.description = String.valueOf(entry.get("description"));
                plan.state = PlanState.valueOf(String.valueOf(entry.get("state")).toUpperCase(Locale.ROOT));
                plans.put(plan.id, plan);
                for (var t : (List<?>) entry.get("tasks")) {
                    var shown = (Map<?, ?>) t;
                    var task = new Task();
                    task.id = String.valueOf(shown.get("id"));
                    task.plan = plan.id;
                    task.title = String.valueOf(shown.get("title"));
                    task.description = String.valueOf(shown.get("description"));
                    task.state = State.valueOf(String.valueOf(shown.get("state")).toUpperCase(Locale.ROOT));
                    task.parent = String.valueOf(shown.get("parent"));
                    task.after = new ArrayList<>(((List<?>) shown.get("after")).stream().map(String::valueOf).toList());
                    for (var l : (List<?>) shown.get("links")) {
                        var link = (Map<?, ?>) l;
                        task.links.add(new Link(String.valueOf(link.get("path")), ((Number) link.get("line")).intValue(),
                                ((Number) link.get("endLine")).intValue(), String.valueOf(link.get("element")), String.valueOf(link.get("note"))));
                    }
                    for (var e : (List<?>) shown.get("timeline")) {
                        var said = (Map<?, ?>) e;
                        task.timeline.add(new Entry(String.valueOf(said.get("at")), String.valueOf(said.get("who")),
                                String.valueOf(said.get("kind")), String.valueOf(said.get("text"))));
                    }
                    tasks.put(task.id, task);
                    plan.tasks.add(task.id);
                }
            }
            for (var s : (List<?>) kept.get("steering")) {
                var steer = (Map<?, ?>) s;
                steering.add(new Steer(((Number) steer.get("n")).intValue(), String.valueOf(steer.get("message")),
                        Boolean.TRUE.equals(steer.get("told"))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the plans in " + file, e);
        }
    }
}
