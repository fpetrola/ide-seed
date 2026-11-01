package ide.mcp;

import ide.core.Json;
import ide.session.Workspace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server over MCP's HTTP transport, on this machine only: a request is a POST, its answer JSON (or a stream of
 * events when there is more than one message), a notification gets 202, and nobody without the token gets anything.
 */
class HttpTest {

    private static final String P = """
            package a;

            public class P {
                public int x;
            }
            """;

    private final Workspace workspace = Workspace.of(Map.of("a/P.java", P));
    private final List<String> turns = new ArrayList<>();
    private final List<String> called = new ArrayList<>();
    private final Http http = Http.start(new Server(workspace, called::add), work -> {
        turns.add("turn");
        work.run();
    }, "secret");
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        http.stop();
    }

    private HttpResponse<String> post(String token, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(http.url())).header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void itListensOnThisMachineOnly() {
        assertThat(http.url()).startsWith("http://127.0.0.1:").endsWith("/mcp");
    }

    @Test
    void aRequestIsAnsweredAsJsonInTheTurnItIsGiven() throws Exception {
        var response = post("secret", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"rename\",\"arguments\":{\"element\":\"a.P#x\",\"name\":\"y\"}}}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(Json.read(response.body()).toString()).contains("isError=false");
        assertThat(workspace.capabilities().invoke("show", Map.of("path", "a/P.java"))).contains("public int y;");
        assertThat(turns).hasSize(1);
        assertThat(called).containsExactly("rename");
    }

    @Test
    void aNotificationIsAcceptedWithNothingToSay() throws Exception {
        var response = post("secret", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void moreThanOneMessageIsAStreamOfEvents() throws Exception {
        var define = Json.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", "define",
                "arguments", Map.of("text", """
                        refactoring make-private "Makes a member private"
                          param member : element(FIELD,METHOD) "the member"
                          set ${member} visibility private
                        end
                        """))));

        var response = post("secret", define);

        assertThat(response.headers().firstValue("Content-Type")).hasValue("text/event-stream");
        assertThat(response.body().lines().filter(line -> line.startsWith("data: ")).toList()).hasSize(2)
                .last().asString().contains("notifications/tools/list_changed");
    }

    @Test
    void withoutTheTokenNothingIsDone() throws Exception {
        var response = post("wrong", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"rename\",\"arguments\":{\"element\":\"a.P#x\",\"name\":\"y\"}}}");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(post(null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").statusCode()).isEqualTo(401);
        assertThat(turns).isEmpty();
    }

    @Test
    void thereIsNoStreamToListenTo() throws Exception {
        var get = HttpRequest.newBuilder(URI.create(http.url())).header("Authorization", "Bearer secret").GET().build();

        assertThat(client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(405);
    }
}
