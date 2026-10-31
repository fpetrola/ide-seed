package ide.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Append-only JSONL log of everything that happens; {@link #NONE} discards. */
public interface EventLog {

    EventLog NONE = event -> {
    };

    void append(Map<String, ?> event);

    static EventLog to(Path file) {
        return event -> {
            var line = new LinkedHashMap<String, Object>();
            line.put("at", Instant.now().toString());
            line.putAll(event);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.write(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
