package ide.mcp;

import ide.core.Json;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The IDE as the tools of an MCP server: JSON-RPC lines in, JSON-RPC lines out. */
class ServerTest {

    private static final String P = """
            package a;

            public class P {
                public int x;
            }
            """;

    /** What the server answers to these requests, one JSON-RPC message per line, read. */
    private static List<Map<String, Object>> exchange(Workspace workspace, String... requests) {
        var out = new StringWriter();
        new Server(workspace).run(new BufferedReader(new StringReader(String.join("\n", requests) + "\n")), new PrintWriter(out, true));
        return out.toString().lines().map(line -> {
            @SuppressWarnings("unchecked") var message = (Map<String, Object>) Json.read(line);
            return message;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    @Test
    void itIntroducesItselfWithTheVersionItWasAskedForAndToolsThatChange() {
        var answers = exchange(Workspace.of(Map.of("a/P.java", P)),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}");

        assertThat(answers).hasSize(2); // a notification is not answered
        assertThat(answers.getFirst()).containsEntry("id", 1L);
        assertThat(result(answers.getFirst())).containsEntry("protocolVersion", "2025-03-26")
                .containsEntry("capabilities", Map.of("tools", Map.of("listChanged", true)));
        assertThat(answers.get(1)).containsEntry("id", 2L).containsEntry("result", Map.of());
    }

    /** What the person said in the window and Claude was not told yet goes along with the next answer, once. */
    @Test
    void whatThePersonSaidGoesAlongWithTheNextAnswerOnce() {
        var workspace = Workspace.of(Map.of("a/P.java", P));
        workspace.capabilities().invoke("plan-new", Map.of("title", "Encapsulate P", "tasks", "Encapsulate x"));
        workspace.capabilities().invoke("steer", Map.of("kind", "pause", "task", "t1"));
        var call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"ls\",\"arguments\":{}}}";

        var first = exchange(workspace, call).getFirst().toString();
        var second = exchange(workspace, call).getFirst().toString();

        assertThat(first).contains("a/P.java", "From the person, in the IDE", "The person paused task t1");
        assertThat(second).contains("a/P.java").doesNotContain("The person");
    }

    /** A hook telling what the agent did reads no answer: what the person said waits for the agent's next call. */
    @Test
    void whatTheAgentsHooksTellDoesNotTakeWhatThePersonSaid() {
        var workspace = Workspace.of(Map.of("a/P.java", P));
        workspace.capabilities().invoke("plan-new", Map.of("title", "Encapsulate P", "tasks", "Encapsulate x"));
        workspace.capabilities().invoke("steer", Map.of("kind", "pause", "task", "t1"));

        var hook = exchange(workspace, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"activity\","
                + "\"arguments\":{\"event\":{\"tool_name\":\"Read\",\"tool_input\":{\"file_path\":\"a/P.java\"}}}}}").getFirst().toString();

        assertThat(hook).doesNotContain("The person");
        assertThat(workspace.capabilities().invoke("steering", Map.of())).contains("The person paused task t1");
    }

    /** How to work on the IDE is said when an agent arrives, as the IDE's guide says it. */
    @Test
    void itTellsAnArrivingAgentHowToWorkOnTheIde() {
        var workspace = Workspace.of(Map.of("a/P.java", P));
        var answers = exchange(workspace, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");

        assertThat(result(answers.getFirst())).containsEntry("instructions", ide.core.Json.read(workspace.capabilities().invoke("guide", Map.of())));
    }

    @Test
    void everyCapabilityIsAToolWithItsSchema() {
        var answers = exchange(Workspace.of(Map.of("a/P.java", P)), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");

        @SuppressWarnings("unchecked") var tools = (List<Map<String, Object>>) result(answers.getFirst()).get("tools");
        var rename = tools.stream().filter(tool -> tool.get("name").equals("rename")).findFirst().orElseThrow();
        assertThat(rename).containsEntry("description", "Renames an element and every use of it");
        assertThat(rename.get("inputSchema")).isEqualTo(Map.of("type", "object", "required", List.of("element", "name"),
                "properties", Map.of(
                        "element", Map.of("type", "string", "description", "an element, by address: a.B, a.B#field, a.B#method(int)"),
                        "name", Map.of("type", "string"))));
        var extract = tools.stream().filter(tool -> tool.get("name").equals("extract-superclass")).findFirst().orElseThrow();
        assertThat(extract.get("inputSchema").toString()).contains("fields={type=array, items={type=string}");
        var preview = tools.stream().filter(tool -> tool.get("name").equals("preview")).findFirst().orElseThrow();
        assertThat(preview.get("inputSchema").toString()).contains("args={type=object");
    }

    @Test
    void aToolCallAnswersWhatTheCapabilityDoesAndAFailureIsAnErrorResult() {
        var workspace = Workspace.of(Map.of("a/P.java", P));

        var answers = exchange(workspace,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"rename\",\"arguments\":{\"element\":\"a.P#x\",\"name\":\"y\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"rename\",\"arguments\":{\"element\":\"a.P#nothing\",\"name\":\"z\"}}}");

        assertThat(result(answers.getFirst())).containsEntry("isError", false);
        assertThat(result(answers.getFirst()).get("content").toString()).contains("\"applied\":true");
        assertThat(workspace.source("a/P.java")).contains("public int y;");
        assertThat(result(answers.get(1))).containsEntry("isError", true);
        assertThat(result(answers.get(1)).get("content").toString()).contains("no element a.P#nothing");
    }

    /** Not even an error of the JVM (code loaded halfway, a recursion with no end) leaves the agent with no answer. */
    @Test
    void aCallThatBreaksTheJvmsWayIsAnErrorResultToo() {
        var define = Json.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", "define",
                "arguments", Map.of("text", """
                        refactoring forever "Calls itself"
                          param member : element(FIELD) "the member"
                          forever ${member}
                        end
                        """))));

        var answers = exchange(Workspace.of(Map.of("a/P.java", P)), define,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"forever\",\"arguments\":{\"member\":\"a.P#x\"}}}");

        assertThat(result(answers.getLast())).containsEntry("isError", true);
        assertThat(result(answers.getLast()).get("content").toString()).contains("StackOverflowError");
    }

    @Test
    void definingARefactoringChangesTheToolsAndSaysSo() {
        var define = Json.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", "define",
                "arguments", Map.of("text", """
                        refactoring make-private "Makes a member private"
                          param member : element(FIELD,METHOD) "the member"
                          set ${member} visibility private
                        end
                        """))));

        var answers = exchange(Workspace.of(Map.of("a/P.java", P)), define, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        assertThat(answers.get(1)).containsEntry("method", "notifications/tools/list_changed");
        assertThat(result(answers.get(2)).get("tools").toString()).contains("name=make-private");
    }

    @Test
    void whatIsNotAKnownRequestIsAnError() {
        var answers = exchange(Workspace.of(Map.of("a/P.java", P)),
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}",
                "not json");

        assertThat(answers.getFirst()).containsEntry("id", 1L).containsEntry("error", Map.of("code", -32601L, "message", "no method resources/list"));
        assertThat(answers.get(1).get("error").toString()).contains("code=-32700");
    }
}
