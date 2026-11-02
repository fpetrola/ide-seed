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
 * The plan being followed (by a person, or an agent working on the IDE): a title and its tasks in order, each to do,
 * being done or done, with a note. It is kept with the project, and each change is an event of the log.
 */
class WorkPlanTest {

    private static final String P = """
            package a;

            public class P {
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
    private static Map<String, Object> plan(Workspace ws) {
        return (Map<String, Object>) Json.read(ws.capabilities().invoke("plan", Map.of()));
    }

    private static Map<String, Object> task(int n, String text, String state, String note) {
        return Map.of("n", (long) n, "text", text, "state", state, "note", note);
    }

    @Test
    void thereIsNoPlanAtFirst() throws Exception {
        assertThat(plan(open())).isEqualTo(Map.of("title", "", "tasks", List.of(), "done", 0L, "of", 0L));
    }

    @Test
    void aPlanIsATitleAndItsTasksOnePerLineAllToDo() throws Exception {
        var ws = open();

        ws.capabilities().invoke("plan-set", Map.of("title", "Encapsulate the orders", "tasks", """
                Find the public fields
                Encapsulate each one

                Run the tests
                """));

        assertThat(plan(ws)).containsEntry("title", "Encapsulate the orders").containsEntry("done", 0L).containsEntry("of", 3L)
                .containsEntry("tasks", List.of(task(1, "Find the public fields", "todo", ""),
                        task(2, "Encapsulate each one", "todo", ""), task(3, "Run the tests", "todo", "")));
    }

    @Test
    void aTaskIsDoneOrBeingDoneWithANoteAndTheProgressSaysSo() throws Exception {
        var ws = open();
        ws.capabilities().invoke("plan-set", Map.of("title", "T", "tasks", "one\ntwo"));

        ws.capabilities().invoke("task", Map.of("n", "1", "state", "done", "note", "3 fields"));
        ws.capabilities().invoke("task", Map.of("n", "2", "state", "doing"));
        ws.capabilities().invoke("task-add", Map.of("text", "three"));

        assertThat(plan(ws)).containsEntry("done", 1L).containsEntry("of", 3L).containsEntry("tasks", List.of(
                task(1, "one", "done", "3 fields"), task(2, "two", "doing", ""), task(3, "three", "todo", "")));
    }

    @Test
    void aTaskThatIsNotThereIsAnError() throws Exception {
        var ws = open();
        ws.capabilities().invoke("plan-set", Map.of("title", "T", "tasks", "one"));

        assertThatThrownBy(() -> ws.capabilities().invoke("task", Map.of("n", "2", "state", "done")))
                .hasMessageContaining("no task 2");
    }

    @Test
    void thePlanIsKeptWithTheProjectAndEachChangeIsAnEvent() throws Exception {
        var ws = open();
        ws.capabilities().invoke("plan-set", Map.of("title", "T", "tasks", "one\ntwo"));
        ws.capabilities().invoke("task", Map.of("n", "1", "state", "done"));

        assertThat(plan(Workspace.open(root))).isEqualTo(plan(ws));
        assertThat(Files.readAllLines(root.resolve(".ide/events.jsonl")))
                .anyMatch(line -> line.contains("\"event\":\"plan\""))
                .anyMatch(line -> line.contains("\"event\":\"task\"") && line.contains("\"state\":\"done\""));
    }
}
