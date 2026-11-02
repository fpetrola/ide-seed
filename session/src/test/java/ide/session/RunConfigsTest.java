package ide.session;

import ide.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a program is run, kept by name as IntelliJ keeps its run configurations: its class, the program's arguments,
 * the virtual machine's options, the folder it runs in and its environment; one of them chosen.
 */
class RunConfigsTest {

    @TempDir
    Path root;

    private Workspace open() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("a/App.java"), """
                package a;

                public class App {
                    public static void main(String[] args) {
                        System.out.println(String.join("|", args) + " " + System.getProperty("mode") + " " + System.getenv("GREETING")
                                + " " + new java.io.File("").getAbsoluteFile().getName());
                    }
                }
                """);
        Files.writeString(root.resolve("a/Tool.java"), "package a;\n\nclass Tool {\n    public static void main(String[] args) {\n    }\n}\n");
        return Workspace.open(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(Workspace ws, String capability, Map<String, ?> args) {
        return (Map<String, Object>) Json.read(ws.capabilities().invoke(capability, args));
    }

    @SuppressWarnings("unchecked")
    private static String run(Map<String, Object> run) throws Exception {
        var builder = new ProcessBuilder((List<String>) run.get("command")).directory(Path.of(String.valueOf(run.get("directory"))).toFile());
        builder.environment().putAll((Map<String, String>) run.get("environment"));
        var process = builder.start();
        var out = new String(process.getInputStream().readAllBytes()).strip();
        process.waitFor();
        return out;
    }

    @Test
    void thereAreNoneAtFirst() throws Exception {
        assertThat(call(open(), "run-configs", Map.of())).containsEntry("configs", List.of()).containsEntry("chosen", "");
    }

    @Test
    void aConfigurationRunsItsClassWithItsArgumentsOptionsFolderAndEnvironment() throws Exception {
        var ws = open();

        call(ws, "run-config-set", Map.of("name", "App with data", "type", "a.App", "args", "one \"two words\" 'three'",
                "vm", "-Dmode=fast -Xmx64m", "directory", "data", "environment", "GREETING=hi;EMPTY="));
        var run = call(ws, "run-command", Map.of("config", "App with data"));

        assertThat(run).containsEntry("title", "App with data").containsEntry("config", "App with data");
        assertThat(run(run)).isEqualTo("one|two words|three fast hi data");
        assertThat(call(ws, "run-configs", Map.of())).containsEntry("chosen", "App with data");
    }

    @Test
    void runningAClassUsesItsConfigurationOrMakesOneNamedAfterIt() throws Exception {
        var ws = open();
        call(ws, "run-config-set", Map.of("name", "App with data", "type", "a.App", "args", "x"));

        assertThat(call(ws, "run-command", Map.of("type", "a.App"))).containsEntry("config", "App with data");
        var tool = call(ws, "run-command", Map.of("type", "a.Tool"));

        assertThat(tool).containsEntry("config", "Tool");
        assertThat(call(ws, "run-configs", Map.of()).get("configs").toString()).contains("name=Tool", "type=a.Tool");
        assertThat(call(ws, "run-command", Map.of())).containsEntry("config", "Tool"); // the one chosen last
    }

    @Test
    void configurationsAreKeptWithTheProjectAndCanBeRemoved() throws Exception {
        var ws = open();
        call(ws, "run-config-set", Map.of("name", "App", "type", "a.App", "args", "x"));
        call(ws, "run-config-set", Map.of("name", "Tool", "type", "a.Tool"));
        call(ws, "run-config-remove", Map.of("name", "Tool"));

        var again = call(Workspace.open(root), "run-configs", Map.of());

        assertThat(again.get("configs").toString()).contains("name=App", "args=x").doesNotContain("Tool");
        assertThat(again).containsEntry("chosen", "App");
        assertThat(Files.readAllLines(root.resolve(".ide/events.jsonl"))).anyMatch(line -> line.contains("\"event\":\"run-config\""));
    }

    @Test
    void aConfigurationOfWhatDoesNotRunIsRefused() throws Exception {
        var ws = open();

        assertThatThrownBy(() -> ws.capabilities().invoke("run-config-set", Map.of("name", "X", "type", "a.Nothing")))
                .hasMessageContaining("a.Nothing");
        assertThatThrownBy(() -> ws.capabilities().invoke("run-command", Map.of("config", "Missing"))).hasMessageContaining("Missing");
        assertThatThrownBy(() -> ws.capabilities().invoke("run-config-set", Map.of("name", "Q", "type", "a.App", "args", "\"open")))
                .hasMessageContaining("quote");
    }
}
