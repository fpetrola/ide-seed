package ide.session;

import ide.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What was said while a task was being done, read from the agent's transcript (as Claude Code writes it): what the
 * person said, what the agent answered and which tools it used, each under the task it started last and was still
 * doing then; what was said before any or between them belongs to none.
 */
class TalkTest {

    @TempDir
    Path root;

    @TempDir
    Path claude;

    private Path transcript;

    private Workspace open() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), "package a;\n\npublic class P {\n    public int x;\n}\n");
        var ws = Workspace.open(root);
        ws.capabilities().invoke("plan-new", Map.of("title", "Encapsulate P", "tasks", "Encapsulate x\nCheck the callers"));
        transcript = claude.resolve("s1.jsonl");
        Files.writeString(transcript, "{\"type\":\"permission-mode\",\"permissionMode\":\"default\"}\n");
        ws.capabilities().invoke("activity", Map.of("event", Map.of("session_id", "s1", "transcript_path", transcript.toString(),
                "tool_name", "Read", "tool_input", Map.of())));
        return ws;
    }

    /** A line of the transcript, said now. */
    private void said(String type, Object content, Map<String, Object> more) throws Exception {
        Thread.sleep(5);
        var line = new java.util.LinkedHashMap<String, Object>(Map.of("parentUuid", "x", "isSidechain", false, "type", type,
                "message", Map.of("role", type, "content", content), "timestamp", Instant.now().toString()));
        line.putAll(more);
        Files.writeString(transcript, Json.write(line) + "\n", StandardOpenOption.APPEND);
        Thread.sleep(5);
    }

    private void said(String type, Object content) throws Exception {
        said(type, content, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static List<String> talk(Workspace ws, String task) {
        var answer = (Map<String, Object>) Json.read(ws.capabilities().invoke("task-talk", Map.of("task", task)));
        return ((List<Map<String, Object>>) answer.get("messages")).stream()
                .map(m -> m.get("who") + " " + m.get("kind") + ": " + m.get("text")).toList();
    }

    @Test
    void eachTaskHasWhatWasSaidWhileItWasBeingDone() throws Exception {
        var ws = open();
        said("user", "Follow the plan");
        ws.capabilities().invoke("task-update", Map.of("task", "t1", "state", "doing"));
        said("assistant", List.of(Map.of("type", "thinking", "thinking", "hmm"), Map.of("type", "text", "text", "Encapsulating x.")));
        said("assistant", List.of(Map.of("type", "tool_use", "id", "u1", "name", "mcp__ideseed__encapsulate-field",
                "input", Map.of("field", "a.P#x"))));
        said("user", List.of(Map.of("type", "tool_result", "tool_use_id", "u1", "content", "ok")));
        said("user", "keep the setter private");
        said("assistant", List.of(Map.of("type", "text", "text", "Inside a subagent")), Map.of("isSidechain", true));
        ws.capabilities().invoke("task-update", Map.of("task", "t1", "state", "done"));
        said("assistant", List.of(Map.of("type", "text", "text", "Now the callers.")));
        ws.capabilities().invoke("task-update", Map.of("task", "t2", "state", "doing"));
        said("assistant", List.of(Map.of("type", "tool_use", "id", "u2", "name", "Grep", "input", Map.of("pattern", "\\.x\\b"))));

        assertThat(talk(ws, "t1")).containsExactly("agent text: Encapsulating x.", "agent tool: encapsulate-field field=a.P#x",
                "person text: keep the setter private");
        assertThat(talk(ws, "t2")).containsExactly("agent tool: Grep \\.x\\b");
    }

    @Test
    void whatIsWrittenAfterwardsIsReadToo() throws Exception {
        var ws = open();
        ws.capabilities().invoke("task-update", Map.of("task", "t1", "state", "doing"));
        said("assistant", List.of(Map.of("type", "text", "text", "First.")));
        assertThat(talk(ws, "t1")).hasSize(1);

        said("assistant", List.of(Map.of("type", "text", "text", "Second.")));

        assertThat(talk(ws, "t1")).containsExactly("agent text: First.", "agent text: Second.");
    }

    @Test
    void withNoTranscriptKnownATaskHasNothingSaid() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), "package a;\n\npublic class P {\n}\n");
        var ws = Workspace.open(root);
        ws.capabilities().invoke("plan-new", Map.of("title", "Plan", "tasks", "One"));

        assertThat(talk(ws, "t1")).isEmpty();
    }
}
