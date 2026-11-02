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

/** A program runs from a class with a main: the command that runs it, as it is saved, with the project's libraries. */
class RunTest {

    @TempDir
    Path root;

    private Workspace open() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("b"));
        Files.writeString(root.resolve("a/App.java"), """
                package a;

                import b.Greeter;

                public class App {
                    public static void main(String[] args) {
                        System.out.println(new Greeter().greet(args.length == 0 ? "world" : args[0]));
                        System.err.println("in " + new java.io.File("").getAbsolutePath());
                        System.exit(3);
                    }
                }
                """);
        Files.writeString(root.resolve("b/Greeter.java"), """
                package b;

                public class Greeter {
                    public String greet(String who) {
                        return "hello " + who;
                    }
                }
                """);
        return Workspace.open(root);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mains(Workspace ws, Map<String, ?> args) {
        return (List<Map<String, Object>>) Json.read(ws.capabilities().invoke("mains", args));
    }

    @Test
    void theClassesWithAMainAreSaidWithTheirLines() throws Exception {
        var ws = open();

        assertThat(mains(ws, Map.of())).containsExactly(Map.of("type", "a.App", "path", "a/App.java", "line", 5L, "methodLine", 6L));
        assertThat(mains(ws, Map.of("path", "b/Greeter.java"))).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCommandRunsTheProgramFromItsSourcesInTheProjectsFolder() throws Exception {
        var ws = open();

        var run = (Map<String, Object>) Json.read(ws.capabilities().invoke("run-command", Map.of("type", "a.App")));
        var command = (List<String>) run.get("command");
        var process = new ProcessBuilder(command).directory(Path.of(String.valueOf(run.get("directory"))).toFile()).start();
        var out = new String(process.getInputStream().readAllBytes());
        var err = new String(process.getErrorStream().readAllBytes());

        assertThat(run).containsEntry("title", "App");
        assertThat(command.getLast()).isEqualTo(root.resolve("a/App.java").toString());
        assertThat(process.waitFor()).isEqualTo(3);
        assertThat(out).isEqualTo("hello world" + System.lineSeparator());
        assertThat(err.strip()).isEqualTo("in " + root.toRealPath());
    }

    @Test
    void whatHasNoMainDoesNotRun() throws Exception {
        var ws = open();

        assertThatThrownBy(() -> ws.capabilities().invoke("run-command", Map.of("type", "b.Greeter")))
                .hasMessageContaining("b.Greeter").hasMessageContaining("main");
    }
}
