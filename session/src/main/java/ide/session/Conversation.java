package ide.session;

import ide.core.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The agent's conversation on a project, to go on with it when the IDE is opened again: which one it is, as the
 * agent's own tools tell it with each thing they do, and whether it was still open when the IDE closed. Kept in a file
 * with the project, if there is one; a new conversation is an event.
 */
final class Conversation {

    private final Path file;
    private final Consumer<Map<String, Object>> events;
    private String id = "";
    private boolean open;

    Conversation(Path file, Consumer<Map<String, Object>> events) {
        this.file = file;
        this.events = events;
        if (file != null && Files.exists(file)) {
            try {
                var kept = (Map<?, ?>) Json.read(Files.readString(file));
                id = String.valueOf(kept.get("id"));
                open = Boolean.TRUE.equals(kept.get("open"));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the conversation in " + file, e);
            }
        }
    }

    /** The conversation the agent's tools are in; a new one from now on if it is another. */
    void in(String id) {
        if (id == null || id.isBlank() || id.equals(this.id)) return;
        this.id = id;
        save();
        events.accept(Map.of("event", "conversation", "id", id));
    }

    /** Whether it was still open (the agent running) when the IDE closed. */
    void open(boolean open) {
        this.open = open;
        save();
    }

    Map<String, Object> shown() {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("id", id);
        shown.put("open", open);
        return shown;
    }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(shown()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot keep the conversation in " + file, e);
        }
    }
}
