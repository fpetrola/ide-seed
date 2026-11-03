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
 * What a plan did, step by step, to see again: each transaction applied while one of its tasks was being done, what
 * it was given, the place of the code it took, and what it changed in each file, the ones it created too; undone ones
 * are not steps. Seen, nothing changes; going on from a step brings the files back to it, and the steps after it stay
 * as discarded.
 */
class ReplayTest {

    @TempDir
    Path root;

    private static final String P = """
            package a;

            public class P {
                public int x;

                int twice() {
                    return x * 2;
                }
            }
            """;

    private Workspace open() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), P);
        var ws = Workspace.open(root);
        call(ws, "plan-new", Map.of("title", "Tidy P", "tasks", "Encapsulate x\nRename"));
        call(ws, "task-update", Map.of("task", "t1", "state", "doing"));
        return ws;
    }

    private static Object call(Workspace ws, String capability, Map<String, ?> args) {
        return Json.read(ws.capabilities().invoke(capability, args));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Workspace ws) {
        return (List<Map<String, Object>>) ((Map<String, Object>) call(ws, "replay", Map.of("plan", "p1"))).get("steps");
    }

    @Test
    void eachStepSaysWhatRanWhereAndWhatItChanged() throws Exception {
        var ws = open();
        ws.invoke("encapsulate-field", Map.of("field", "a.P#x"));
        call(ws, "task-update", Map.of("task", "t1", "state", "done"));
        call(ws, "task-update", Map.of("task", "t2", "state", "doing"));
        ws.invoke("rename", Map.of("element", "a.P#twice()", "name", "doubled"));

        var steps = steps(ws);

        assertThat(steps).extracting(s -> s.get("n") + " " + s.get("what") + " " + s.get("task"))
                .containsExactly("1 encapsulate-field t1", "2 rename t2");
        assertThat(steps.getFirst().get("where")).asList().extracting(w -> ((Map<?, ?>) w).get("path") + ":" + ((Map<?, ?>) w).get("line"))
                .containsExactly("a/P.java:4");
        @SuppressWarnings("unchecked")
        var second = (Map<String, Object>) call(ws, "replay-step", Map.of("n", "2"));
        @SuppressWarnings("unchecked")
        var changes = (List<Map<String, Object>>) second.get("changes");
        assertThat(changes).extracting(c -> c.get("path")).containsExactly("a/P.java");
        assertThat((String) changes.getFirst().get("before")).contains("int twice()");
        assertThat((String) changes.getFirst().get("after")).contains("int doubled()");
        assertThat((String) changes.getFirst().get("diff")).contains("-    int twice() {", "+    int doubled() {");
    }

    @Test
    void whatWasUndoneIsNoStepAndTheStepsAreKeptForTheNextTime() throws Exception {
        var ws = open();
        ws.invoke("encapsulate-field", Map.of("field", "a.P#x"));
        ws.invoke("rename", Map.of("element", "a.P#twice()", "name", "doubled"));
        ws.undo();

        assertThat(steps(Workspace.open(root))).extracting(s -> s.get("what")).containsExactly("encapsulate-field");
    }

    @Test
    void goingOnFromAStepBringsTheFilesBackToItAndDiscardsTheRest() throws Exception {
        var ws = open();
        ws.invoke("rename", Map.of("element", "a.P#twice()", "name", "doubled"));
        ws.invoke("extract-interface", Map.of("type", "a.P", "name", "Doubles", "methods", List.of("a.P#doubled()")));
        ws.invoke("rename", Map.of("element", "a.P#x", "name", "count"));

        call(ws, "replay-continue", Map.of("n", "1"));

        assertThat(ws.source("a/P.java")).contains("int doubled()", "public int x;").doesNotContain("implements");
        assertThat(ws.files()).doesNotContain("a/Doubles.java");
        assertThat(steps(ws)).extracting(s -> s.get("n") + " " + s.get("discarded")).containsExactly("1 false", "2 true", "3 true");
        assertThat(ws.errors()).isEmpty();
    }

    /** A file changed while the IDE was closed: what a later step left is not there to take back. */
    @Test
    void notWhenAFileChangedOtherwiseSinceItsLastStep() throws Exception {
        var ws = open();
        ws.invoke("rename", Map.of("element", "a.P#twice()", "name", "doubled"));
        ws.invoke("rename", Map.of("element", "a.P#x", "name", "count"));
        ws.save();
        Files.writeString(root.resolve("a/P.java"), ws.source("a/P.java").replace("return count * 2;", "return count * 3;"));
        var reopened = Workspace.open(root);

        assertThatThrownBy(() -> call(reopened, "replay-continue", Map.of("n", "1"))).hasMessageContaining("a/P.java");
        assertThat(reopened.source("a/P.java")).contains("count * 3");
    }

    /** What changes on disk while the IDE is open is taken as an edit by hand: a step too. */
    @Test
    void anEditTakenFromDiskIsAStep() throws Exception {
        var ws = open();
        ws.invoke("rename", Map.of("element", "a.P#twice()", "name", "doubled"));
        ws.save();
        Files.writeString(root.resolve("a/P.java"), ws.source("a/P.java").replace("return x * 2;", "return x * 3;"));
        ws.sync();

        assertThat(steps(ws)).extracting(s -> s.get("what")).containsExactly("rename", "edit");
    }
}
