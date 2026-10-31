package ide.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UndoAndLogTest {

    private static final String P = """
            package a;

            class P {
                int x;

                int get() {
                    return x;
                }
            }
            """;

    @Test
    void undoRevertsAppliedTransactionsInReverseOrder() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        ws.invoke("rename", Map.of("element", "a.P#x", "name", "y"));
        ws.invoke("rename", Map.of("element", "a.P#y", "name", "z"));
        ws.undo();

        assertThat(ws.source("a/P.java")).contains("int y;", "return y;");
        ws.undo();
        assertThat(ws.source("a/P.java")).isEqualTo(P);
        assertThatThrownBy(ws::undo).hasMessageContaining("nothing to undo");
    }

    @Test
    void abortedTransactionsAreNotUndoable() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var aborted = ws.invoke("rename", Map.of("element", "a.P#x", "name", "1x"));

        assertThat(aborted.applied()).isFalse();
        assertThat(aborted.violations().getFirst().code()).isEqualTo("projection.invalid-identifier");
        assertThatThrownBy(ws::undo).hasMessageContaining("nothing to undo");
    }

    @Test
    void everyTransactionIsAppendedToTheEventLog(@TempDir Path project) throws Exception {
        var ws = Workspace.of(Map.of("a/P.java", P)).logTo(project.resolve(".ide/events.jsonl"));

        ws.invoke("rename", Map.of("element", "a.P#x", "name", "y"));
        ws.invoke("rename", Map.of("element", "a.P#y", "name", "1y"));
        ws.undo();

        var lines = Files.readAllLines(project.resolve(".ide/events.jsonl"));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).contains("\"event\":\"transaction\"", "\"refactoring\":\"rename\"", "\"applied\":true",
                "\"steps\":[\"' rename a.P#x -> y\"]");
        assertThat(lines.get(1)).contains("\"applied\":false", "projection.invalid-identifier");
        assertThat(lines.get(2)).contains("\"event\":\"undo\"");
    }
}
