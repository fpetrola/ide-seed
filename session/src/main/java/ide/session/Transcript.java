package ide.session;

import ide.core.Json;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The agent's conversation as Claude Code writes it, one JSON object a line: what the person said, what the agent
 * answered, and the tools it used (said as steps are). Its thinking, what its subagents said and its own bookkeeping are
 * left out. Read as it grows: only what was written since the last reading, and the lines that can matter are the
 * only ones parsed (others, like the requests it keeps, are large).
 */
final class Transcript {

    /** Something said: when, by whom (person or agent), what kind (text or tool), and what. */
    record Said(Instant at, String who, String kind, String text) {
    }

    private final Path file;
    private final String folder;
    private final List<Said> said = new ArrayList<>();
    private long read;

    Transcript(Path file, String folder) {
        this.file = file;
        this.folder = folder;
    }

    Path file() {
        return file;
    }

    /** Everything said so far, reading what was written since the last time. */
    List<Said> said() {
        try {
            if (!Files.exists(file)) return List.of();
            var size = Files.size(file);
            if (size < read) { // written anew
                said.clear();
                read = 0;
            }
            if (size > read) {
                try (var in = new RandomAccessFile(file.toFile(), "r")) {
                    in.seek(read);
                    var bytes = new byte[(int) (size - read)];
                    in.readFully(bytes);
                    var text = new String(bytes, StandardCharsets.UTF_8);
                    var end = text.lastIndexOf('\n');
                    if (end < 0) return List.copyOf(said); // a line still being written
                    text.substring(0, end).lines().forEach(this::take);
                    read += text.substring(0, end + 1).getBytes(StandardCharsets.UTF_8).length;
                }
            }
        } catch (IOException unreadable) {
            // what was read stays; the rest, next time
        }
        return List.copyOf(said);
    }

    private void take(String line) {
        if (!line.contains("\"type\":\"user\"") && !line.contains("\"type\":\"assistant\"")) return;
        Map<?, ?> entry;
        try {
            entry = (Map<?, ?>) Json.read(line);
        } catch (RuntimeException unreadable) {
            return;
        }
        var type = String.valueOf(entry.get("type"));
        if (!type.equals("user") && !type.equals("assistant")) return;
        if (Boolean.TRUE.equals(entry.get("isSidechain")) || Boolean.TRUE.equals(entry.get("isMeta"))) return;
        if (!(entry.get("message") instanceof Map<?, ?> message)) return;
        Instant at;
        try {
            at = Instant.parse(String.valueOf(entry.get("timestamp")));
        } catch (RuntimeException noTime) {
            return;
        }
        var who = type.equals("user") ? "person" : "agent";
        var content = message.get("content");
        if (content instanceof String text) {
            text(at, who, text);
            return;
        }
        if (!(content instanceof List<?> blocks)) return;
        for (var block : blocks) {
            if (!(block instanceof Map<?, ?> part)) continue;
            switch (String.valueOf(part.get("type"))) {
                case "text" -> text(at, who, String.valueOf(part.get("text")));
                case "tool_use" -> Activity.step(Map.of("tool_name", String.valueOf(part.get("name")),
                                "tool_input", part.get("input") instanceof Map<?, ?> input ? input : Map.of()), folder)
                        .ifPresent(step -> said.add(new Said(at, who, "tool", step)));
                default -> { } // thinking, tool results
            }
        }
    }

    private void text(Instant at, String who, String text) {
        var said = text.strip();
        if (said.isEmpty() || said.startsWith("<")) return; // what Claude Code wraps: commands and their output
        this.said.add(new Said(at, who, "text", said));
    }
}
