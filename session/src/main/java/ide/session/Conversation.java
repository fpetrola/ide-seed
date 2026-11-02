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
 * The agent's conversation on a project, to go on with it when the IDE is opened again: which one it is and where it
 * is written, as the agent's own tools tell it with each thing they do, and whether it was still open when the IDE closed. Kept in a file
 * with the project, if there is one; a new conversation is an event.
 */
final class Conversation {

    private final Path file;
    private final Consumer<Map<String, Object>> events;
    private String id = "";
    private String transcript = "";
    private boolean open;

    Conversation(Path file, Consumer<Map<String, Object>> events) {
        this.file = file;
        this.events = events;
        if (file != null && Files.exists(file)) {
            try {
                var kept = (Map<?, ?>) Json.read(Files.readString(file));
                id = String.valueOf(kept.get("id"));
                transcript = kept.get("transcript") == null ? "" : String.valueOf(kept.get("transcript"));
                open = Boolean.TRUE.equals(kept.get("open"));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the conversation in " + file, e);
            }
        }
    }

    /** The conversation the agent's tools are in; a new one from now on if it is another. */
    void in(String id, String transcript) {
        var known = transcript == null || transcript.isBlank() ? this.transcript : transcript;
        if (id == null || id.isBlank() || id.equals(this.id) && known.equals(this.transcript)) return;
        var another = !id.equals(this.id);
        this.id = id;
        this.transcript = known;
        save();
        if (another) events.accept(Map.of("event", "conversation", "id", id));
    }

    /** Where the agent writes the conversation, if it is known. */
    String transcript() {
        return transcript;
    }

    /** Whether it was still open (the agent running) when the IDE closed. */
    void open(boolean open) {
        this.open = open;
        save();
    }

    Map<String, Object> shown() {
        var shown = new LinkedHashMap<String, Object>();
        shown.put("id", id);
        shown.put("transcript", transcript);
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
