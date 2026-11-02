package ide.session;

import ide.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What changed on disk while the IDE was open (another tool, an agent editing files): taken as an edit by hand, so the
 * program keeps who its elements are, unless the IDE changed the same file too, which is said and left alone.
 */
class SyncTest {

    private static final String P = """
            package a;

            public class P {
                public int x;

                int get() {
                    return x;
                }
            }
            """;

    @TempDir
    Path root;

    private Workspace open() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), P);
        return Workspace.open(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sync(Workspace ws) {
        return (Map<String, Object>) Json.read(ws.capabilities().invoke("sync", Map.of()));
    }

    @Test
    void aFileChangedOnDiskIsAnEditByHandThatKeepsWhoItsElementsAre() throws Exception {
        var ws = open();
        var x = ws.capabilities().invoke("elements", Map.of());
        Files.writeString(root.resolve("a/P.java"), P.replace("x", "total"));

        var synced = sync(ws);

        assertThat(synced).containsEntry("taken", java.util.List.of("a/P.java"));
        assertThat(ws.source("a/P.java")).contains("public int total;").contains("return total;");
        assertThat(ws.diff()).isEmpty(); // what is on disk is what is saved
        assertThat(ws.capabilities().invoke("elements", Map.of())).isEqualTo(x.replace("x", "total"));
    }

    @Test
    void aNewFileOnDiskIsANewSource() throws Exception {
        var ws = open();
        Files.writeString(root.resolve("a/Q.java"), "package a;\n\nclass Q {\n    int y = new P().get();\n}\n");

        var synced = sync(ws);

        assertThat(synced).containsEntry("taken", java.util.List.of("a/Q.java"));
        assertThat(ws.files()).containsExactlyInAnyOrder("a/P.java", "a/Q.java");
    }

    @Test
    void nothingChangedIsNothingToDo() throws Exception {
        var ws = open();
        ws.capabilities().invoke("rename", Map.of("element", "a.P#x", "name", "y"));
        ws.save();

        assertThat(sync(ws)).containsEntry("taken", java.util.List.of()).containsEntry("conflicts", java.util.List.of());
    }

    @Test
    void aFileChangedOnDiskAndInTheIdeIsAConflictLeftAlone() throws Exception {
        var ws = open();
        ws.capabilities().invoke("rename", Map.of("element", "a.P#x", "name", "y"));
        Files.writeString(root.resolve("a/P.java"), P.replace("get", "value"));

        var synced = sync(ws);

        assertThat(synced).containsEntry("conflicts", java.util.List.of("a/P.java")).containsEntry("taken", java.util.List.of());
        assertThat(ws.source("a/P.java")).contains("public int y;").contains("int get()");
        assertThat(Files.readString(root.resolve("a/P.java"))).contains("int value()");
    }

    @Test
    void aFileDeletedOnDiskIsSaidNotFollowed() throws Exception {
        var ws = open();
        Files.delete(root.resolve("a/P.java"));

        assertThat(sync(ws)).containsEntry("deleted", java.util.List.of("a/P.java"));
        assertThat(ws.files()).containsExactly("a/P.java");
    }

    @Test
    void takingAChangeIsAnEventOfTheLog() throws Exception {
        var ws = open();
        Files.writeString(root.resolve("a/P.java"), P.replace("x", "total"));

        sync(ws);

        assertThat(Files.readAllLines(root.resolve(".ide/events.jsonl"))).anyMatch(line -> line.contains("\"event\":\"sync\""));
    }
}
