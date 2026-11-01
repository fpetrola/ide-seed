package ide.mcp;

import ide.core.Capability;
import ide.core.Json;
import ide.core.Param;
import ide.session.Workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The IDE as a Model Context Protocol server: JSON-RPC 2.0 messages, one per line, on its input and its output. Every
 * capability is a tool and its parameters a JSON Schema; a call answers what the capability answers, as text, or the
 * error as an error result. When a call changes the tools (a refactoring defined), the client is told.
 */
public final class Server {

    /** The protocol's versions this server speaks, the latest first. */
    static final List<String> VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private static final String ELEMENT = "an element, by address: a.B, a.B#field, a.B#method(int)";

    private final Workspace workspace;

    public Server(Workspace workspace) {
        this.workspace = workspace;
    }

    /** Answers every request it reads until its input ends; a notification is not answered. */
    public void run(BufferedReader in, PrintWriter out) {
        try {
            for (var line = in.readLine(); line != null; line = in.readLine()) {
                if (line.isBlank()) continue;
                Map<?, ?> message;
                try {
                    message = (Map<?, ?>) Json.read(line);
                } catch (IllegalArgumentException | ClassCastException unreadable) {
                    out.println(Json.write(error(null, -32700, "not a JSON-RPC message: " + unreadable.getMessage())));
                    continue;
                }
                if (!message.containsKey("id")) continue;
                answer(message, out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void answer(Map<?, ?> request, PrintWriter out) {
        var id = request.get("id");
        var method = String.valueOf(request.get("method"));
        var params = request.get("params") instanceof Map<?, ?> given ? given : Map.of();
        switch (method) {
            case "initialize" -> out.println(Json.write(result(id, Map.of(
                    "protocolVersion", VERSIONS.contains(params.get("protocolVersion")) ? params.get("protocolVersion") : VERSIONS.getFirst(),
                    "capabilities", Map.of("tools", Map.of("listChanged", true)),
                    "serverInfo", Map.of("name", "ideseed", "version", "0.1")))));
            case "ping" -> out.println(Json.write(result(id, Map.of())));
            case "tools/list" -> out.println(Json.write(result(id, Map.of("tools",
                    workspace.capabilities().ids().stream().map(name -> tool(workspace.capabilities().get(name))).toList()))));
            case "tools/call" -> {
                var tools = workspace.capabilities().ids();
                out.println(Json.write(result(id, call(String.valueOf(params.get("name")), params.get("arguments")))));
                if (!workspace.capabilities().ids().equals(tools)) {
                    out.println(Json.write(Map.of("jsonrpc", "2.0", "method", "notifications/tools/list_changed")));
                }
            }
            default -> out.println(Json.write(error(id, -32601, "no method " + method)));
        }
    }

    /** What a capability answers, as text; what goes wrong, as an error result the agent can read. */
    private Map<String, Object> call(String name, Object arguments) {
        try {
            @SuppressWarnings("unchecked") var args = arguments instanceof Map<?, ?> given ? (Map<String, ?>) given : Map.<String, Object>of();
            return Map.of("content", List.of(Map.of("type", "text", "text", workspace.capabilities().invoke(name, args))), "isError", false);
        } catch (RuntimeException failed) {
            return Map.of("content", List.of(Map.of("type", "text", "text", String.valueOf(failed.getMessage()))), "isError", true);
        }
    }

    /** A capability as a tool: its name, what it does, and its parameters as a JSON Schema. */
    private static Map<String, Object> tool(Capability capability) {
        var properties = new LinkedHashMap<String, Object>();
        capability.params().forEach(param -> properties.put(param.name(), schema(param)));
        var input = new LinkedHashMap<String, Object>();
        input.put("type", "object");
        input.put("properties", properties);
        input.put("required", capability.params().stream().filter(Param::required).map(Param::name).toList());
        return Map.of("name", capability.id(), "description", capability.description(), "inputSchema", input);
    }

    private static Map<String, Object> schema(Param param) {
        var schema = new LinkedHashMap<String, Object>();
        var kinds = param.kinds().isEmpty() ? "" : " (" + String.join(", ", param.kinds()) + ")";
        var described = param.description().isEmpty() ? "" : param.description() + ": ";
        switch (param.type()) {
            case "element" -> {
                schema.put("type", "string");
                schema.put("description", described + ELEMENT + kinds);
            }
            case "elements" -> {
                schema.put("type", "array");
                schema.put("items", Map.of("type", "string"));
                schema.put("description", described + "elements, each " + ELEMENT.substring(ELEMENT.indexOf(',') + 2) + kinds);
            }
            case "bool" -> schema.put("type", "boolean");
            case "choice" -> {
                schema.put("type", "string");
                schema.put("enum", param.kinds());
            }
            case "map" -> schema.put("type", "object");
            default -> schema.put("type", "string");
        }
        if (!schema.containsKey("description") && !param.description().isEmpty()) schema.put("description", param.description());
        return schema;
    }

    private static Map<String, Object> result(Object id, Object result) {
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
