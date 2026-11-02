package ide.session;

import ide.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent's conversation on a project, to go on with it when the IDE is opened again: which one it was (as the
 * agent's own tools tell it), and whether it was still open when the IDE closed.
 */
class ConversationTest {

    @TempDir
    Path root;

    private Workspace open() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), "package a;\n\npublic class P {\n}\n");
        return Workspace.open(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(Workspace ws, String capability, Map<String, ?> args) {
        return (Map<String, Object>) Json.read(ws.capabilities().invoke(capability, args));
    }

    @Test
    void thereIsNoneAtFirst() throws Exception {
        assertThat(call(open(), "conversation", Map.of())).containsEntry("id", "").containsEntry("open", false);
    }

    @Test
    void theConversationIsTheOneTheAgentsToolsSayAndItIsKeptWithTheProject() throws Exception {
        var ws = open();

        call(ws, "activity", Map.of("event", Map.of("session_id", "d94e78d7", "tool_name", "Read", "tool_input", Map.of("file_path", "x"))));
        call(ws, "conversation-open", Map.of("open", "true"));

        assertThat(call(Workspace.open(root), "conversation", Map.of())).containsEntry("id", "d94e78d7").containsEntry("open", true);
    }

    @Test
    void anotherConversationIsTheOneFromThenOn() throws Exception {
        var ws = open();
        call(ws, "activity", Map.of("event", Map.of("session_id", "first", "tool_name", "Read", "tool_input", Map.of())));

        call(ws, "activity", Map.of("event", Map.of("session_id", "second", "tool_name", "Read", "tool_input", Map.of())));

        assertThat(call(ws, "conversation", Map.of())).containsEntry("id", "second");
    }
}
