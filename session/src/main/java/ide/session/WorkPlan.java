package ide.session;

import ide.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The plan being followed, by a person or an agent working on the IDE: a title and its tasks in order, each to do,
 * being done or done, with a note. It knows nothing of programs. Kept in a file with the project, if there is one; each
 * change is told as an event.
 */
public final class WorkPlan {

    public enum State {
        TODO, DOING, DONE;

        static State of(String name) {
            return valueOf(name.toUpperCase(Locale.ROOT));
        }

        String said() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Task(String text, State state, String note) {
    }

    private final Path file;
    private final Consumer<Map<String, Object>> events;
    private String title = "";
    private final List<Task> tasks = new ArrayList<>();

    /** A plan kept in {@code file} (none: kept nowhere), read from it if it is there, telling each change to {@code events}. */
    WorkPlan(Path file, Consumer<Map<String, Object>> events) {
        this.file = file;
        this.events = events;
        if (file != null && Files.exists(file)) read();
    }

    /** A new plan: its title and its tasks, every one to do. */
    void set(String title, List<String> texts) {
        this.title = title;
        tasks.clear();
        texts.stream().map(String::strip).filter(text -> !text.isEmpty()).forEach(text -> tasks.add(new Task(text, State.TODO, "")));
        changed(Map.of("event", "plan", "title", title, "tasks", tasks.size()));
    }

    void add(String text) {
        tasks.add(new Task(text.strip(), State.TODO, ""));
        changed(Map.of("event", "task", "n", tasks.size(), "text", text.strip(), "state", "todo"));
    }

    /** Task {@code n} (from 1) in a state, with a note if one is given. */
    void task(int n, State state, String note) {
        if (n < 1 || n > tasks.size()) throw new IllegalArgumentException("no task " + n + ": the plan has " + tasks.size());
        var was = tasks.get(n - 1);
        tasks.set(n - 1, new Task(was.text(), state, note == null ? was.note() : note));
        changed(Map.of("event", "task", "n", n, "text", was.text(), "state", state.said()));
    }

    /** The plan as the capabilities answer it: title, tasks numbered from 1, and how many are done of how many. */
    Map<String, Object> shown() {
        var listed = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < tasks.size(); i++) {
            var task = tasks.get(i);
            var entry = new LinkedHashMap<String, Object>();
            entry.put("n", i + 1);
            entry.put("text", task.text());
            entry.put("state", task.state().said());
            entry.put("note", task.note());
            listed.add(entry);
        }
        var shown = new LinkedHashMap<String, Object>();
        shown.put("title", title);
        shown.put("tasks", listed);
        shown.put("done", tasks.stream().filter(task -> task.state() == State.DONE).count());
        shown.put("of", tasks.size());
        return shown;
    }

    private void changed(Map<String, Object> event) {
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.write(shown()));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot keep the plan in " + file, e);
            }
        }
        events.accept(event);
    }

    private void read() {
        try {
            var kept = (Map<?, ?>) Json.read(Files.readString(file));
            title = String.valueOf(kept.get("title"));
            for (var task : (List<?>) kept.get("tasks")) {
                var entry = (Map<?, ?>) task;
                tasks.add(new Task(String.valueOf(entry.get("text")), State.of(String.valueOf(entry.get("state"))),
                        String.valueOf(entry.get("note"))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the plan in " + file, e);
        }
    }
}
