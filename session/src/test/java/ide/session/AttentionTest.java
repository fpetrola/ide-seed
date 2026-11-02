package ide.session;

import ide.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Where attention is, both ways: an agent points the person at a place (an element, or lines of a file) with a note,
 * and asks where the person is looking (the file, the caret, what is selected, and what that is in the program).
 */
class AttentionTest {

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
    private static Map<String, Object> call(Workspace ws, String capability, Map<String, ?> args) {
        return (Map<String, Object>) Json.read(ws.capabilities().invoke(capability, args));
    }

    @Test
    void nothingIsPointedAtFirst() throws Exception {
        assertThat(call(open(), "pointed", Map.of())).containsEntry("n", 0L);
    }

    @Test
    void pointingAtAnElementIsPointingWhereItIsDeclared() throws Exception {
        var ws = open();

        var pointed = call(ws, "point", Map.of("element", "a.P#get()", "note", "this one reads x"));

        assertThat(pointed).containsEntry("n", 1L).containsEntry("path", "a/P.java").containsEntry("line", 6L)
                .containsEntry("endLine", 6L).containsEntry("note", "this one reads x").containsEntry("element", "a.P#get()");
        assertThat(call(ws, "pointed", Map.of())).isEqualTo(pointed);
    }

    @Test
    void pointingAtLinesOfAFile() throws Exception {
        var ws = open();
        call(ws, "point", Map.of("element", "a.P#x"));

        var pointed = call(ws, "point", Map.of("path", "a/P.java", "line", "6", "end", "8"));

        assertThat(pointed).containsEntry("n", 2L).containsEntry("line", 6L).containsEntry("endLine", 8L).containsEntry("note", "");
    }

    @Test
    void whatIsNotThereCannotBePointedAt() throws Exception {
        var ws = open();

        assertThatThrownBy(() -> ws.capabilities().invoke("point", Map.of("path", "a/Q.java", "line", "1")))
                .hasMessageContaining("a/Q.java");
        assertThatThrownBy(() -> ws.capabilities().invoke("point", Map.of("path", "a/P.java", "line", "40")))
                .hasMessageContaining("40");
        assertThatThrownBy(() -> ws.capabilities().invoke("point", Map.of("note", "here")))
                .hasMessageContaining("element").hasMessageContaining("path");
        assertThat(call(ws, "pointed", Map.of())).containsEntry("n", 0L);
    }

    @Test
    void pointingIsAnEventOfTheLog() throws Exception {
        var ws = open();

        call(ws, "point", Map.of("element", "a.P#x", "note", "public"));

        assertThat(Files.readAllLines(root.resolve(".ide/events.jsonl"))).anyMatch(line -> line.contains("\"event\":\"point\""));
    }

    @Test
    void whereThePersonLooksIsSaidWithWhatItIsInTheProgram() throws Exception {
        var ws = open();
        assertThat(call(ws, "looking", Map.of())).containsEntry("path", "");

        ws.capabilities().invoke("look", Map.of("path", "a/P.java", "line", "7", "column", "16", "selected", "7-7"));

        assertThat(call(ws, "looking", Map.of())).containsEntry("path", "a/P.java").containsEntry("line", 7L)
                .containsEntry("column", 16L).containsEntry("selected", "7-7")
                .containsEntry("element", "a.P#x").containsEntry("inside", "a.P#get()");
    }
}
