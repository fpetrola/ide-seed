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
 * The plans a person and an agent follow on the IDE: several, one active; each a tree of tasks (lines of action and
 * their subtasks) that wait for others, with stable ids, a description, links into the code and a timeline. What the
 * person says about them (pause, resume, comment, replan, switch) waits to be told to the agent.
 */
class PlansTest {

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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Workspace ws, String capability, Map<String, ?> args) {
        return (List<Map<String, Object>>) Json.read(ws.capabilities().invoke(capability, args));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasks(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("tasks");
    }

    private static List<String> outline(Map<String, Object> plan) {
        return tasks(plan).stream().map(t -> "  ".repeat(((Number) t.get("depth")).intValue()) + t.get("id") + " " + t.get("title")
                + " [" + t.get("state") + "]").toList();
    }

    private Workspace encapsulating() throws Exception {
        var ws = open();
        call(ws, "plan-new", Map.of("title", "Encapsulate P", "description", "No public fields in P", "tasks", """
                Find the public fields
                Encapsulate them
                  Encapsulate x
                  Check the callers
                Run the tests
                """));
        return ws;
    }

    @Test
    void thereAreNoPlansAtFirst() throws Exception {
        var ws = open();

        assertThat(list(ws, "plans", Map.of())).isEmpty();
        assertThat(call(ws, "plan", Map.of())).containsEntry("id", "");
    }

    @Test
    void aPlanIsATreeOfTasksWrittenOnePerLineIndentedUnderTheirLine() throws Exception {
        var ws = encapsulating();

        var plan = call(ws, "plan", Map.of());

        assertThat(plan).containsEntry("id", "p1").containsEntry("title", "Encapsulate P").containsEntry("state", "active")
                .containsEntry("description", "No public fields in P").containsEntry("done", 0L).containsEntry("of", 5L);
        assertThat(outline(plan)).containsExactly("t1 Find the public fields [todo]", "t2 Encapsulate them [todo]",
                "  t3 Encapsulate x [todo]", "  t4 Check the callers [todo]", "t5 Run the tests [todo]");
        assertThat(tasks(plan).get(2)).containsEntry("parent", "t2");
    }

    @Test
    void aTaskIsSeenWithEverythingItHas() throws Exception {
        var ws = encapsulating();
        call(ws, "task-update", Map.of("task", "t3", "state", "doing", "note", "x is read in get()"));
        call(ws, "task-link", Map.of("task", "t3", "element", "a.P#x", "note", "the field"));
        call(ws, "task-update", Map.of("task", "t5", "after", "t2", "description", "mvn test, all green"));

        var t3 = call(ws, "task", Map.of("task", "t3"));
        var t5 = call(ws, "task", Map.of("task", "t5"));

        assertThat(t3).containsEntry("id", "t3").containsEntry("plan", "p1").containsEntry("title", "Encapsulate x")
                .containsEntry("state", "doing").containsEntry("parent", "t2");
        assertThat(t3.get("links").toString()).contains("a/P.java", "line=4", "a.P#x", "the field");
        assertThat(t3.get("timeline").toString()).contains("state", "doing", "x is read in get()", "agent");
        assertThat(t5).containsEntry("after", List.of("t2")).containsEntry("description", "mvn test, all green")
                .containsEntry("waiting", List.of("t2"));
    }

    @Test
    void aLineOfActionGoesAsFarAsItsSubtasks() throws Exception {
        var ws = encapsulating();
        call(ws, "task-update", Map.of("task", "t3", "state", "done"));

        var t2 = tasks(call(ws, "plan", Map.of())).get(1);

        assertThat(t2).containsEntry("done", 1L).containsEntry("of", 2L);
    }

    @Test
    void replanningKeepsWhoEachTaskIs() throws Exception {
        var ws = encapsulating();
        call(ws, "task-update", Map.of("task", "t1", "state", "done", "note", "only x"));

        var added = call(ws, "task-add", Map.of("title", "Make get() use the getter", "parent", "t2"));
        call(ws, "task-update", Map.of("task", "t4", "state", "dropped", "note", "no callers outside P"));
        call(ws, "task-update", Map.of("task", "t5", "parent", "t2"));

        assertThat(added).containsEntry("id", "t6");
        assertThat(outline(call(ws, "plan", Map.of()))).containsExactly("t1 Find the public fields [done]",
                "t2 Encapsulate them [todo]", "  t3 Encapsulate x [todo]", "  t4 Check the callers [dropped]",
                "  t6 Make get() use the getter [todo]", "  t5 Run the tests [todo]");
        assertThat(call(ws, "task", Map.of("task", "t1")).get("timeline").toString()).contains("only x");
    }

    @Test
    void whatWouldBreakTheTreeIsRefused() throws Exception {
        var ws = encapsulating();

        assertThatThrownBy(() -> call(ws, "task-update", Map.of("task", "t2", "parent", "t3"))).hasMessageContaining("t2").hasMessageContaining("t3");
        assertThatThrownBy(() -> call(ws, "task-update", Map.of("task", "t9", "state", "done"))).hasMessageContaining("no task t9");
        call(ws, "task-update", Map.of("task", "t5", "after", "t1"));
        assertThatThrownBy(() -> call(ws, "task-update", Map.of("task", "t1", "after", "t5"))).hasMessageContaining("waits");
    }

    @Test
    void anotherPlanBecomesTheActiveOneAndTheFormerWaitsPaused() throws Exception {
        var ws = encapsulating();

        call(ws, "plan-new", Map.of("title", "Rename the orders", "tasks", "Rename Order\nRename its uses"));

        assertThat(list(ws, "plans", Map.of())).extracting(p -> p.get("id") + " " + p.get("state") + " " + p.get("done") + "/" + p.get("of"))
                .containsExactly("p1 paused 0/5", "p2 active 0/2");
        assertThat(call(ws, "plan", Map.of())).containsEntry("id", "p2");
        assertThat(call(ws, "plan", Map.of("plan", "p1"))).containsEntry("title", "Encapsulate P");
        assertThat(tasks(call(ws, "plan", Map.of())).getFirst()).containsEntry("id", "t6");

        call(ws, "plan-switch", Map.of("plan", "p1"));

        assertThat(list(ws, "plans", Map.of())).extracting(p -> p.get("id") + " " + p.get("state"))
                .containsExactly("p1 active", "p2 paused");
    }

    @Test
    void whatThePersonSaysWaitsToBeToldToTheAgentOnce() throws Exception {
        var ws = encapsulating();

        var paused = call(ws, "steer", Map.of("kind", "pause", "task", "t3"));
        call(ws, "steer", Map.of("kind", "comment", "task", "t4", "text", "the callers are in B too"));

        assertThat(paused.get("message").toString()).contains("paused", "t3", "Encapsulate x");
        assertThat(call(ws, "task", Map.of("task", "t3"))).containsEntry("state", "paused");
        assertThat(call(ws, "task", Map.of("task", "t4")).get("timeline").toString()).contains("person", "the callers are in B too");
        assertThat(list(ws, "steering", Map.of())).extracting(s -> s.get("message").toString())
                .satisfiesExactly(m -> assertThat(m).contains("t3"), m -> assertThat(m).contains("the callers are in B too"));
        assertThat(list(ws, "steering", Map.of())).isEmpty();
    }

    @Test
    void whatTheWindowAlreadyToldTheAgentDoesNotWait() throws Exception {
        var ws = encapsulating();

        call(ws, "steer", Map.of("kind", "replan", "plan", "p1", "text", "do the callers first", "told", "true"));

        assertThat(list(ws, "steering", Map.of())).isEmpty();
    }

    @Test
    void pausingAndResumingAPlan() throws Exception {
        var ws = encapsulating();

        call(ws, "steer", Map.of("kind", "pause", "plan", "p1"));
        assertThat(call(ws, "plan", Map.of())).containsEntry("state", "paused");

        var resumed = call(ws, "steer", Map.of("kind", "resume", "plan", "p1"));
        assertThat(call(ws, "plan", Map.of())).containsEntry("state", "active");
        assertThat(resumed.get("message").toString()).contains("resumed", "Encapsulate P");
    }

    @Test
    void thePlansAreKeptWithTheProjectAndEachChangeIsAnEvent() throws Exception {
        encapsulating();
        var again = Workspace.open(root);

        assertThat(outline(call(again, "plan", Map.of()))).hasSize(5);
        assertThat(call(again, "task-add", Map.of("title", "One more"))).containsEntry("id", "t6");
        assertThat(Files.readAllLines(root.resolve(".ide/events.jsonl"))).anyMatch(line -> line.contains("\"event\":\"plan\""))
                .anyMatch(line -> line.contains("\"event\":\"task\""));
    }
}
