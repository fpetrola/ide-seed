package ide.ui;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The window without a screen: a project, its editors, its views, and the keys that act on them. */
class WorkbenchTest {

    private static final String A = """
            package a;

            public class A {
                public int x;

                int m(int p) {
                    return x + p;
                }
            }
            """;
    private static final String B = """
            package a;

            class B {
                int n(A a) {
                    return a.m(1);
                }
            }
            """;

    @TempDir
    Path root;

    private final ScriptedDialogs dialogs = new ScriptedDialogs();

    private Workbench workbench() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/A.java"), A);
        Files.writeString(root.resolve("a/B.java"), B);
        return new Workbench(new Ide(Workspace.open(root)), dialogs);
    }

    @Test
    void theProjectShowsItsFilesAndAFileOpensInAnEditor() throws IOException {
        var workbench = workbench();

        workbench.open("a/A.java", 7, 16);

        assertThat(workbench.files()).containsExactly("a/A.java", "a/B.java");
        assertThat(workbench.text("a/A.java")).isEqualTo(A);
        assertThat(workbench.where()).isEqualTo("a/A.java:7:16");
        assertThat(workbench.views().output()).startsWith("libraries: none (no pom.xml in ");
    }

    @Test
    void renamingTheElementUnderTheCaretChangesTheEditorsThatShowIt() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 18);
        workbench.open("a/A.java", 4, 16); // public int x: on x
        dialogs.line("count");

        workbench.act("rename");

        assertThat(dialogs.asked).containsExactly("Rename a.A#x [x]");
        assertThat(workbench.renaming).isEqualTo(2); // the field and its read, marked while the name was asked
        assertThat(workbench.text("a/A.java")).contains("public int count;", "return count + p;");
        assertThat(workbench.views().steps()).contains("' rename a.A#x -> count");
        assertThat(workbench.views().changes()).contains("+    public int count;");
    }

    @Test
    void aLocalIsRenamedWhereItIsWithItsUses() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/C.java"), """
                package a;

                class C {
                    int sum(int[] values) {
                        int total = 0;
                        for (int v : values) total += v;
                        return total;
                    }
                }
                """);
        var workbench = workbench();
        workbench.open("a/C.java", 5, 13); // int total = 0: on total
        dialogs.line("acc");

        workbench.act("rename");

        assertThat(dialogs.asked).containsExactly("Rename a.C#sum(int[])/total [total]");
        assertThat(workbench.renaming).isEqualTo(3);
        assertThat(workbench.text("a/C.java")).contains("int acc = 0;", "for (int v : values) acc += v;", "return acc;");
    }

    @Test
    void inABodyTheMethodIsWhatTheCaretIsInside() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/C.java"), """
                package a;

                class C {
                    int twice(int n) {
                        int sum = n + n;
                        return sum * 2;
                    }
                }
                """);
        var workbench = workbench();
        workbench.open("a/C.java", 5, 23); // int sum = n + n: on the second n
        workbench.select(5, 9, 5, 24);
        dialogs.fill(Map.of());

        workbench.act("extract-method");

        assertThat(dialogs.filled.getFirst().fields()).extracting(f -> f.param().name() + "=" + f.value())
                .containsExactly("method=a.C#twice(int)", "lines=5-5", "name=extracted");
    }

    @Test
    void theInlineKeyOnALocalPutsItsValueWhereItIsRead() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/C.java"), """
                package a;

                class C {
                    int twice(int n) {
                        int sum = n + n;
                        return sum * 2;
                    }
                }
                """);
        var workbench = workbench();
        workbench.open("a/C.java", 6, 16); // return sum * 2: on sum
        dialogs.fill(Map.of());

        workbench.act("inline");

        assertThat(dialogs.filled.getFirst().fields()).extracting(f -> f.param().name() + "=" + f.value())
                .containsExactly("local=a.C#twice(int)/sum");
        assertThat(workbench.text("a/C.java")).contains("        return (n + n) * 2;\n").doesNotContain("sum");
    }

    @Test
    void anAppliedEditShowsItsProblemsAndAQuickFixTakesThemAway() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 16);
        workbench.type("a/B.java", B.replace("return a.m(1);", "return total;"));

        workbench.act("apply-edit");

        assertThat(workbench.views().problems()).singleElement().asString().startsWith("a/B.java:5: total cannot be resolved");
        assertThat(workbench.marks()).containsExactly(java.util.Map.entry("a", "ERROR"), java.util.Map.entry("a/B.java", "ERROR"));
        dialogs.choose("create field total in B").line("int");

        workbench.act("context-actions");

        assertThat(workbench.views().problems()).isEmpty();
        assertThat(workbench.marks()).isEmpty();
        assertThat(workbench.text("a/B.java")).contains("int total;");
    }

    @Test
    void whatIsWrittenHalfwayStaysInTheEditorWhateverIsAsked() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 16);
        var halfway = B.replace("return a.m(1);", "return a.m(1) +");
        workbench.type("a/B.java", halfway);

        workbench.act("context-actions");
        workbench.act("extend-selection");
        workbench.act("save");
        workbench.act("rename");
        workbench.act("introduce-constant");
        workbench.check();

        assertThat(workbench.text("a/B.java")).isEqualTo(halfway);
        assertThat(dialogs.told).isEmpty();
        assertThat(workbench.views().problems()).anySatisfy(p -> assertThat(p).startsWith("a/B.java:5:"));
    }

    @Test
    void anEditorClosesAndWhatItSaidIsAppliedFirst() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 16);
        workbench.open("a/A.java", 4, 16);
        workbench.type("a/A.java", A.replace("public int x;", "public int x, y;"));

        workbench.act("close-editor");

        assertThat(workbench.editors().open()).containsExactly("a/B.java");
        assertThat(workbench.editors().current()).contains("a/B.java");
        workbench.open("a/A.java", 1, 1);
        assertThat(workbench.text("a/A.java")).contains("public int x, y;");
    }

    @Test
    void anEditorWithWhatTheProgramDoesNotTakeYetStaysOpen() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 4, 16);
        var halfway = A.replace("public int x;", "public int x +");
        workbench.type("a/A.java", halfway);

        workbench.act("close-editor");

        assertThat(workbench.editors().open()).containsExactly("a/A.java");
        assertThat(workbench.text("a/A.java")).isEqualTo(halfway);
    }

    @Test
    void undoingAChangeThatMadeAFileClosesItsEditor() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 3, 14); // public class A: on A
        dialogs.fill(Map.of("name", "Base", "fields", java.util.List.of("a.A#x")));
        workbench.act("refactor:extract-superclass");
        assertThat(workbench.files()).contains("a/Base.java");
        workbench.open("a/Base.java", 1, 1);

        workbench.act("undo-change");

        assertThat(workbench.files()).doesNotContain("a/Base.java");
        assertThat(workbench.editors().open()).containsExactly("a/A.java");
        assertThat(workbench.text("a/A.java")).isEqualTo(A);
    }

    @Test
    void f3GoesToTheDeclarationAndBackComesBack() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 18); // a.m(1): on m

        workbench.act("open-declaration");
        assertThat(workbench.where()).isEqualTo("a/A.java:6:9");

        workbench.act("back");
        assertThat(workbench.where()).isEqualTo("a/B.java:5:18");
    }

    @Test
    void theRefactorKeyOffersWhatTakesTheElementUnderTheCaret() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 4, 16); // a field

        workbench.act("refactor");

        assertThat(dialogs.offered.getFirst()).contains("Rename...", "Encapsulate Field...", "Pull Up Field...")
                .doesNotContain("Extract Superclass...", "Pull Up Method...", "Ls...");
    }

    @Test
    void referencesAndCallersAreResultsThatOpenWhereTheyAre() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 6, 9); // int m(int p): on m

        workbench.act("callers");

        assertThat(workbench.views().results()).containsExactly("a.B#n(A)");
        workbench.views().openResult(0);
        assertThat(workbench.where()).isEqualTo("a/B.java:4:9");
    }

    @Test
    void everyKeyIsBound() throws IOException {
        var workbench = workbench();

        for (var key : Keymap.KEYS) {
            assertThat(workbench.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key.stroke())).as(key.action())
                    .isEqualTo(key.action());
            assertThat(workbench.getActionMap().get(key.action())).as(key.action()).isNotNull();
        }
    }

    /** Alt+F12 shows the terminal among the views; its shell starts only once it is on a screen. */
    @Test
    void theTerminalIsAViewThatStartsWhenShown() throws IOException {
        var workbench = workbench();

        workbench.act("terminal");

        var terminal = workbench.terminalView().orElseThrow();
        assertThat(workbench.views().getSelectedComponent()).isSameAs(terminal);
        assertThat(workbench.views().getTitleAt(workbench.views().getSelectedIndex())).isEqualTo("Terminal");
        assertThat(terminal.running()).isFalse();
    }

    /**
     * Served to agents, the window is the IDE they work on: a tool called over MCP changes the program the editors
     * show, and Claude Code starts in the terminal knowing where to find it.
     */
    @Test
    void anAgentWorksOnTheIdeTheWindowShows(@TempDir Path served) throws Exception {
        var workbench = workbench();
        workbench.open("a/A.java", 4, 16);
        try {
            var config = workbench.serve(served);

            assertThat(java.nio.file.Files.getPosixFilePermissions(config))
                    .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            @SuppressWarnings("unchecked") var server = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                    ide.core.Json.read(Files.readString(config))).get("mcpServers")).get("ideseed");
            @SuppressWarnings("unchecked") var headers = (Map<String, Object>) server.get("headers");
            var call = java.net.http.HttpRequest.newBuilder(java.net.URI.create(String.valueOf(server.get("url"))))
                    .header("Authorization", String.valueOf(headers.get("Authorization")))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                            + "\"params\":{\"name\":\"rename\",\"arguments\":{\"element\":\"a.A#x\",\"name\":\"count\"}}}")).build();
            var answer = java.net.http.HttpClient.newHttpClient().send(call, java.net.http.HttpResponse.BodyHandlers.ofString());
            javax.swing.SwingUtilities.invokeAndWait(() -> { }); // the window has done what the call asked

            assertThat(answer.body()).contains("\"isError\":false");
            assertThat(workbench.text("a/A.java")).contains("public int count;").contains("return count + p;");
            assertThat(workbench.views().output()).contains("agent: rename");
            assertThat(Files.readString(root.resolve("a/A.java"))).contains("public int count;"); // on disk at once

            workbench.act("claude");

            assertThat(String.join(" ", workbench.terminalView().orElseThrow().first()))
                    .contains("claude --mcp-config \"$IDESEED_MCP_CONFIG\" --settings \"$IDESEED_SETTINGS\"");
        } finally {
            workbench.closeDown();
        }
        assertThat(served).isEmptyDirectory();
    }

    /**
     * An agent asks where the person is looking, and gets the element under the caret; it points the person at lines
     * of another file, with a note, and the window opens them selected and says the note.
     */
    @Test
    void anAgentAndThePersonShowEachOtherWhereToLook(@TempDir Path served) throws Exception {
        var workbench = workbench();
        workbench.open("a/A.java", 7, 16);
        try {
            var config = workbench.serve(served);

            var looking = agent(config, "looking", Map.of());

            assertThat(looking).contains("\"path\":\"a/A.java\"", "\"line\":7", "\"element\":\"a.A#x\"");

            agent(config, "point", Map.of("path", "a/B.java", "line", "4", "end", "6", "note", "the only caller of m"));

            assertThat(workbench.where()).startsWith("a/B.java:");
            assertThat(workbench.editors().selectedLines()).hasValue("4-6");
            assertThat(workbench.views().output()).contains("look at a/B.java:4-6: the only caller of m");
        } finally {
            workbench.closeDown();
        }
    }

    /** The text an agent gets calling a tool of the IDE the window serves, once the window has done it. */
    @SuppressWarnings("unchecked")
    private static String agent(Path config, String tool, Map<String, ?> arguments) throws Exception {
        var server = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                ide.core.Json.read(Files.readString(config))).get("mcpServers")).get("ideseed");
        var headers = (Map<String, Object>) server.get("headers");
        var body = ide.core.Json.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)));
        var call = java.net.http.HttpRequest.newBuilder(java.net.URI.create(String.valueOf(server.get("url"))))
                .header("Authorization", String.valueOf(headers.get("Authorization")))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var answer = java.net.http.HttpClient.newHttpClient().send(call, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
        assertThat(answer).contains("\"isError\":false");
        var result = (Map<String, Object>) ((Map<String, Object>) ide.core.Json.read(answer)).get("result");
        return String.valueOf(((Map<String, Object>) ((java.util.List<?>) result.get("content")).get(0)).get("text"));
    }

    private Workspace planned() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/A.java"), A);
        var workspace = Workspace.open(root);
        workspace.capabilities().invoke("plan-new", Map.of("title", "Encapsulate A", "description", "No public fields in A",
                "tasks", "Find its public fields\nEncapsulate x\n  Its uses\n  Its tests"));
        workspace.capabilities().invoke("task-update", Map.of("task", "t1", "state", "done", "note", "only x"));
        workspace.capabilities().invoke("task-update", Map.of("task", "t2", "state", "doing", "description", "a getter and a setter"));
        workspace.capabilities().invoke("task-update", Map.of("task", "t4", "after", "t3"));
        workspace.capabilities().invoke("task-link", Map.of("task", "t2", "element", "a.A#x", "note", "the field"));
        return workspace;
    }

    /** The plans beside the editors: which one goes on, how far it got, and its lines of action as a tree. */
    @Test
    void thePlansAreATreeOfLinesOfActionBesideTheEditors() throws IOException {
        var workbench = new Workbench(new Ide(planned()), dialogs);

        assertThat(workbench.plans().lines()).containsExactly("Encapsulate A (active) 1 of 4 done",
                "t1 [done] Find its public fields", "t2 [doing] Encapsulate x 0/2", "  t3 [todo] Its uses",
                "  t4 [todo] Its tests (waits t3)");
    }

    /** A plan stopped is still the one that goes on: it is shown, paused. */
    @Test
    void aStoppedPlanIsStillShown() throws IOException {
        var workspace = planned();
        workspace.capabilities().invoke("steer", Map.of("kind", "stop", "plan", "p1"));

        var workbench = new Workbench(new Ide(workspace), dialogs);

        assertThat(workbench.plans().lines()).first().isEqualTo("Encapsulate A (paused) 1 of 4 done");
    }

    /** Choosing a task shows everything it has; a link opens the code it is about. */
    @Test
    void aTaskIsSeenInDetailAndItsLinksOpenTheCode() throws IOException {
        var workbench = new Workbench(new Ide(planned()), dialogs);

        workbench.plans().select("t2");

        assertThat(String.join("\n", workbench.plans().detail())).contains("t2 Encapsulate x", "doing", "a getter and a setter",
                "a.A#x", "the field", "agent", "doing");

        workbench.plans().follow(0);

        assertThat(workbench.where()).startsWith("a/A.java:4:");
        assertThat(workbench.editors().selectedLines()).hasValue("4-4");
    }

    /** The details are in parts: subtasks to go to, long notes cut until opened, only the last steps until asked. */
    @Test
    void aTaskIsReadInPartsAndLongTextIsCutUntilOpened() throws IOException {
        var workspace = planned();
        var long_ = "word ".repeat(60).strip();
        workspace.capabilities().invoke("task-update", Map.of("task", "t2", "note", long_));
        for (var i = 1; i <= 7; i++) {
            workspace.capabilities().invoke("activity", Map.of("event", Map.of("tool_name", "Bash", "tool_input", Map.of("command", "step " + i))));
        }
        var workbench = new Workbench(new Ide(workspace), dialogs);
        workbench.plans().select("t2");

        assertThat(workbench.plans().detail()).contains("now: Bash: step 7", "subtask t3 [todo] Its uses")
                .anySatisfy(line -> assertThat(line).endsWith("step: Bash: step 3"))
                .noneSatisfy(line -> assertThat(line).endsWith("step: Bash: step 2"));
        assertThat(workbench.plans().html().replaceAll("\\s+", " ")).contains("more:").doesNotContain(long_);

        workbench.plans().click("steps");
        assertThat(workbench.plans().detail()).anySatisfy(line -> assertThat(line).endsWith("step: Bash: step 1"));
        var more = java.util.regex.Pattern.compile("more:\\d+").matcher(workbench.plans().html()).results().findFirst().orElseThrow().group();
        workbench.plans().click(more);
        assertThat(workbench.plans().html().replaceAll("\\s+", " ")).contains(long_);

        workbench.plans().click("task:t3");
        assertThat(workbench.plans().detail()).first().isEqualTo("t3 Its uses");
    }

    /** What the person says from the window changes the plan at once and waits to be told to Claude. */
    @Test
    void thePersonSteersFromTheWindow() throws IOException {
        var workspace = planned();
        var workbench = new Workbench(new Ide(workspace), dialogs);
        workbench.plans().select("t3");

        workbench.plans().act("pause");
        dialogs.line("the callers are in B too");
        workbench.plans().act("comment");

        assertThat(workbench.plans().lines()).contains("  t3 [paused] Its uses");
        assertThat(String.join("\n", workbench.plans().detail())).contains("person", "the callers are in B too");
        assertThat(workbench.views().output()).contains("for Claude, at its next step: The person paused task t3");
        assertThat(workspace.capabilities().invoke("steering", Map.of())).contains("paused task t3", "the callers are in B too");
    }

    /** Another plan can be the one that goes on, and the plan as a whole paused, resumed or replanned. */
    @Test
    void thePersonSwitchesPlans() throws IOException {
        var workspace = planned();
        workspace.capabilities().invoke("plan-new", Map.of("title", "Rename B", "tasks", "Rename it"));
        var workbench = new Workbench(new Ide(workspace), dialogs);

        assertThat(workbench.plans().plansListed()).containsExactly("Encapsulate A (paused) 1/4", "Rename B (active) 0/1");

        workbench.plans().choose("p1");
        workbench.plans().act("start-plan");

        assertThat(workbench.plans().plansListed()).containsExactly("Encapsulate A (active) 1/4", "Rename B (paused) 0/1");
        dialogs.line("do its tests first");
        workbench.plans().act("replan-plan");
        assertThat(workspace.capabilities().invoke("steering", Map.of())).contains("started plan p1", "replan plan p1", "do its tests first");
    }

    /** Claude Code's hook, as the window writes it, tells the IDE each tool Claude used: a step of the task it is doing. */
    @Test
    void whatClaudeDoesIsAStepOfItsTaskThroughItsHook(@TempDir Path served) throws Exception {
        var workspace = planned();
        var workbench = new Workbench(new Ide(workspace), dialogs);
        try {
            workbench.serve(served);
            Path settings;
            try (var files = Files.list(served)) {
                settings = files.filter(f -> f.toString().endsWith("-settings.json")).findFirst().orElseThrow();
            }
            assertThat(Files.getPosixFilePermissions(settings)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.readString(settings)).contains("\"allow\":[\"mcp__ideseed\"]");
            @SuppressWarnings("unchecked") var hook = (String) ((Map<String, Object>) ((List<?>) ((Map<String, Object>) ((List<?>)
                    ((Map<String, Object>) ((Map<String, Object>) ide.core.Json.read(Files.readString(settings))).get("hooks"))
                            .get("PostToolUse")).getFirst()).get("hooks")).getFirst()).get("command");
            var event = "{\"session_id\":\"s\",\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\"" + root.resolve("a/A.java")
                    + "\",\"old_string\":\"x\",\"new_string\":\"" + "y".repeat(200_000) + "\"}}";

            var process = new ProcessBuilder("sh", "-c", hook).redirectErrorStream(true).start();
            try (var in = process.getOutputStream()) {
                in.write(event.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertThat(process.waitFor()).as(new String(process.getInputStream().readAllBytes())).isZero();
            javax.swing.SwingUtilities.invokeAndWait(() -> { });

            assertThat(workspace.capabilities().invoke("task", Map.of("task", "t2"))).contains("\"kind\":\"step\",\"text\":\"Edit a/A.java\"");
            assertThat(workbench.plans().lines()).contains("t2 [doing] Encapsulate x 0/2 - Edit a/A.java");
        } finally {
            workbench.closeDown();
        }
        assertThat(served).isEmptyDirectory();
    }

    /** Where Claude Code keeps a conversation of this project, in a Claude home made for the test. */
    private Path conversationFile(Path home, String id) throws IOException {
        var kept = home.resolve("projects").resolve(root.toRealPath().toString().replaceAll("[^A-Za-z0-9]", "-"));
        Files.createDirectories(kept);
        return Files.writeString(kept.resolve(id + ".jsonl"), "{}\n");
    }

    private String claudeStarted(Workbench workbench, String action) {
        workbench.act(action);
        return String.join(" ", workbench.terminalView().orElseThrow().first());
    }

    /** A task shows what was said while it was being done: the person's messages, Claude's answers, its tools as one line. */
    @Test
    void aTaskShowsTheConversationHadWhileItWasBeingDone(@TempDir Path home) throws Exception {
        var workspace = planned();
        var transcript = conversationFile(home, "s1");
        workspace.capabilities().invoke("conversation-in", Map.of("id", "s1", "transcript", transcript.toString()));
        workspace.capabilities().invoke("task-update", Map.of("task", "t3", "state", "doing"));
        Thread.sleep(5);
        java.util.function.BiConsumer<String, Object> said = (type, content) -> {
            try {
                Files.writeString(transcript, ide.core.Json.write(Map.of("type", type, "isSidechain", false, "timestamp",
                        java.time.Instant.now().toString(), "message", Map.of("role", type, "content", content))) + "\n",
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        said.accept("user", "and the uses in B?");
        said.accept("assistant", List.of(Map.of("type", "text", "text", "B reads x twice; I will make it call getX().")));
        for (var file : List.of("a/A.java", "a/B.java", "a/C.java")) {
            said.accept("assistant", List.of(Map.of("type", "tool_use", "id", file, "name", "Read", "input", Map.of("file_path", file))));
        }
        var workbench = new Workbench(new Ide(workspace), dialogs);

        workbench.plans().select("t3");

        assertThat(workbench.plans().conversation()).extracting(line -> line.substring(6)).containsExactly("person text: and the uses in B?",
                "agent text: B reads x twice; I will make it call getX().", "agent tool: Read a/A.java", "agent tool: Read a/B.java",
                "agent tool: Read a/C.java");
        assertThat(workbench.plans().conversationHtml().replaceAll("\\s+", " ")).contains("+2 tools").doesNotContain("a/C.java");
        var tools = java.util.regex.Pattern.compile("tools:\\d+").matcher(workbench.plans().conversationHtml()).results().findFirst().orElseThrow().group();
        workbench.plans().click(tools);
        assertThat(workbench.plans().conversationHtml()).contains("a/C.java");
    }

    /** Claude Code goes on with the conversation it had on the project: the one its tools last said, or its latest. */
    @Test
    void claudeGoesOnWithItsConversationOnTheProject(@TempDir Path served, @TempDir Path home) throws Exception {
        var workspace = planned();
        var workbench = new Workbench(new Ide(workspace), dialogs);
        workbench.claudeHome(home);
        try {
            workbench.serve(served);
            assertThat(claudeStarted(workbench, "claude")).doesNotContain("--resume");

            var latest = conversationFile(home, "latest");
            assertThat(claudeStarted(workbench, "claude")).contains("claude --resume latest --mcp-config");
            assertThat(workspace.capabilities().invoke("conversation", Map.of())).contains(latest.toString()); // to read it

            conversationFile(home, "told");
            Files.setLastModifiedTime(conversationFile(home, "newer"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));
            workspace.capabilities().invoke("activity", Map.of("event", Map.of("session_id", "told", "tool_name", "Read", "tool_input", Map.of())));
            assertThat(claudeStarted(workbench, "claude")).contains("--resume told ");

            assertThat(claudeStarted(workbench, "claude-new")).doesNotContain("--resume");
        } finally {
            workbench.closeDown();
        }
    }

    /** If Claude was still working when the IDE closed, opening the project again goes on with it. */
    @Test
    void openedAgainClaudeGoesOnIfItWasWorkingWhenTheIdeClosed(@TempDir Path served, @TempDir Path home) throws Exception {
        var workspace = planned();
        conversationFile(home, "d94e78d7");
        workspace.capabilities().invoke("activity", Map.of("event", Map.of("session_id", "d94e78d7", "tool_name", "Read", "tool_input", Map.of())));
        var before = new Workbench(new Ide(workspace), dialogs);
        before.claudeHome(home);
        before.closeDown();
        var quiet = new Workbench(new Ide(Workspace.open(root)), dialogs);
        quiet.claudeHome(home);
        quiet.reopened();
        assertThat(quiet.terminalView().orElseThrow().first()).isNotEmpty().noneMatch(part -> part.contains("claude"));
        quiet.closeDown();

        Workspace.open(root).capabilities().invoke("conversation-open", Map.of("open", "true"));
        var again = new Workbench(new Ide(Workspace.open(root)), dialogs);
        again.claudeHome(home);
        try {
            again.serve(served);
            again.reopened();

            assertThat(String.join(" ", again.terminalView().orElseThrow().first())).contains("claude --resume d94e78d7");
            assertThat(again.views().output()).contains("going on with Claude's conversation d94e78d7");
        } finally {
            again.closeDown();
        }
    }

    private Workbench runnable() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/A.java"), A);
        Files.writeString(root.resolve("a/App.java"), """
                package a;

                public class App {
                    public static void main(String[] args) throws Exception {
                        System.out.println("x is " + new A().x);
                        System.err.println("careful");
                        var line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
                        System.out.println("read " + line);
                        if ("forever".equals(line)) Thread.sleep(60_000);
                        System.exit(2);
                    }
                }
                """);
        return new Workbench(new Ide(Workspace.open(root)), dialogs);
    }

    /** A class with a main shows IntelliJ's green arrows; clicking one runs it, and the console shows what it writes. */
    @Test
    void aClassWithAMainRunsFromItsGreenArrowAndTheConsoleShowsWhatItWrites() throws Exception {
        var workbench = runnable();
        workbench.open("a/App.java", 1, 1);
        workbench.open("a/A.java", 1, 1);

        assertThat(workbench.editors().runs("a/App.java")).containsExactly(3, 4);
        assertThat(workbench.editors().runs("a/A.java")).isEmpty();

        workbench.open("a/App.java", 1, 1);
        workbench.editors().clickRun("a/App.java", 4);
        var console = workbench.console();
        var until = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!console.text().contains("careful") && System.nanoTime() < until) {
            Thread.sleep(20);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> console.send("hello"));
        console.await(java.time.Duration.ofSeconds(20));

        assertThat(workbench.views().getTitleAt(workbench.views().getSelectedIndex())).isEqualTo("Run");
        assertThat(console.text()).contains("x is 0", "careful", "hello", "read hello", "Process finished with exit code 2");
        assertThat(console.errors()).isEqualTo("careful" + System.lineSeparator());
        assertThat(console.exit()).hasValue(2);
    }

    /** A program that does not end is stopped; what has no main says so; a run runs the code as the editor says it. */
    @Test
    void aRunIsStoppedAndRunsWhatTheEditorSays() throws Exception {
        var workbench = runnable();
        workbench.open("a/A.java", 1, 1);
        workbench.act("run");
        assertThat(workbench.views().output()).contains("nothing to run in a/A.java");

        workbench.open("a/App.java", 5, 1);
        workbench.type("a/App.java", workbench.text("a/App.java").replace("new A().x);", "new A().x + \"!\");")); // not applied yet
        workbench.act("run");
        var console = workbench.console();
        var until = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!console.text().contains("careful") && System.nanoTime() < until) {
            Thread.sleep(20);
            javax.swing.SwingUtilities.invokeAndWait(() -> { });
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> console.send("forever"));
        javax.swing.SwingUtilities.invokeAndWait(() -> workbench.act("stop-run"));
        console.await(java.time.Duration.ofSeconds(10));

        assertThat(console.text()).contains("x is 0!");
        assertThat(Files.readString(root.resolve("a/App.java"))).contains("new A().x + \"!\"");
        assertThat(console.exit()).isPresent().get().isNotEqualTo(0).isNotEqualTo(2);
        assertThat(console.running()).isFalse();
    }

    private void awaitFinished(Workbench workbench) throws Exception {
        workbench.console().await(java.time.Duration.ofSeconds(30));
    }

    /** A run configuration is made and edited as IntelliJ's are, and runs with its arguments, options, folder and environment. */
    @Test
    void aRunConfigurationRunsWithWhatItSays() throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("a/Echo.java"), """
                package a;

                public class Echo {
                    public static void main(String[] args) {
                        System.out.println(String.join("|", args) + " " + System.getProperty("mode") + " " + System.getenv("GREETING")
                                + " " + new java.io.File("").getAbsoluteFile().getName());
                    }
                }
                """);
        var workbench = new Workbench(new Ide(Workspace.open(root)), dialogs);
        workbench.open("a/Echo.java", 1, 1);
        assertThat(workbench.configsListed()).containsExactly("Edit Configurations\u2026");

        dialogs.choose("+ New configuration").fill(Map.of("name", "Echo twice", "args", "one \"two words\"", "vm", "-Dmode=slow",
                "directory", "data", "environment", "GREETING=hola"));
        workbench.act("edit-configs");

        assertThat(dialogs.filled.getLast().fields()).anySatisfy(field -> {
            assertThat(field.param().name()).isEqualTo("type");
            assertThat(field.value()).isEqualTo("a.Echo"); // the class of the file open, to begin with
            assertThat(field.options()).containsExactly("a.Echo");
        });
        assertThat(workbench.configsListed()).containsExactly("Echo twice", "Edit Configurations\u2026");

        workbench.act("run-chosen");
        awaitFinished(workbench);

        assertThat(workbench.console().text()).contains("one|two words slow hola data");
        assertThat(workbench.console().title()).isEqualTo("Echo twice");
    }

    /** Running a class from its arrow uses its configuration, or makes one named after it, chosen from then on. */
    @Test
    void aClassRunFromItsArrowGetsAConfigurationAndOneIsRemoved() throws Exception {
        var workbench = runnable();
        workbench.open("a/App.java", 1, 1);

        workbench.editors().clickRun("a/App.java", 3);
        javax.swing.SwingUtilities.invokeAndWait(() -> workbench.act("stop-run"));
        awaitFinished(workbench);

        assertThat(workbench.configsListed()).containsExactly("App", "Edit Configurations\u2026");

        dialogs.choose("\u2212 Remove a configuration").choose("App");
        workbench.act("edit-configs");

        assertThat(workbench.configsListed()).containsExactly("Edit Configurations\u2026");
    }

    /** What another tool writes in the project's folder is taken as an edit by hand, and the editor shows it. */
    @Test
    void whatChangesOnDiskTheWindowShows() throws Exception {
        var workbench = workbench();
        workbench.open("a/A.java", 4, 16);
        workbench.watch();
        try {
            Files.writeString(root.resolve("a/A.java"), A.replace("int m(int p)", "int twice(int p)"));

            var until = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            var shown = new String[1];
            do {
                Thread.sleep(50);
                javax.swing.SwingUtilities.invokeAndWait(() -> shown[0] = workbench.text("a/A.java"));
            } while (!shown[0].contains("twice") && System.nanoTime() < until);

            assertThat(shown[0]).contains("int twice(int p)");
            assertThat(workbench.views().output()).contains("from disk: a/A.java");
            assertThat(workbench.views().changes()).isEmpty(); // what is on disk is what is saved
        } finally {
            workbench.closeDown();
        }
    }

    /** In a window the size of the one Main opens, the editors take most of the height; the views below, the rest. */
    @Test
    void theEditorsTakeMostOfTheHeight() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 1, 1);
        workbench.setSize(1400, 860);
        layOut(workbench);

        var withPlans = (javax.swing.JSplitPane) java.util.Arrays.stream(workbench.getComponents())
                .filter(javax.swing.JSplitPane.class::isInstance).map(javax.swing.JSplitPane.class::cast)
                .findFirst().orElseThrow().getRightComponent();
        var center = (javax.swing.JSplitPane) withPlans.getLeftComponent();
        var editors = center.getTopComponent().getHeight();
        var views = center.getBottomComponent().getHeight();

        assertThat(editors).as("editors %d, views %d", editors, views).isGreaterThanOrEqualTo(3 * views);
    }

    private static void layOut(java.awt.Container container) {
        container.doLayout();
        for (var child : container.getComponents()) {
            if (child instanceof java.awt.Container inner) layOut(inner);
        }
    }

    /** A change the program makes is undone as a change, not as typing: the editor never shows an empty file. */
    @Test
    void ctrlZWithNothingTypedUndoesTheLastChange() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 4, 16);
        dialogs.line("count");
        workbench.act("rename");

        var area = (javax.swing.text.JTextComponent) find(workbench, org.fife.ui.rsyntaxtextarea.RSyntaxTextArea.class);
        area.getActionMap().get("undo-typing-or-change").actionPerformed(null);

        assertThat(workbench.text("a/A.java")).isEqualTo(A);
    }

    private static java.awt.Component find(java.awt.Container container, Class<?> type) {
        for (var child : container.getComponents()) {
            if (type.isInstance(child)) return child;
            if (child instanceof java.awt.Container inner) {
                var found = find(inner, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void altEnterOffersTheFixesOfTheLineAndTheRefactoringsOfTheCaret() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 4, 16); // public int x: on x

        workbench.act("context-actions");

        assertThat(dialogs.offered.getFirst()).contains("Generate Getter...", "Encapsulate Field...", "Rename...", "Safe Delete...")
                .doesNotContain("Pull Up Method...");
        assertThat(workbench.bulbShown()).contains("INTENTION");
    }

    @Test
    void aLineWithAProblemShowsTheRedBulb() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 16);
        workbench.type("a/B.java", B.replace("return a.m(1);", "return total;"));
        workbench.act("apply-edit");

        assertThat(workbench.bulbShown()).contains("FIX");
    }

    @Test
    void aClickOnTheBulbOffersWhatAltEnterOffers() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 16);
        workbench.type("a/B.java", B.replace("return a.m(1);", "return total;"));
        workbench.act("apply-edit");
        assertThat(workbench.bulbShown()).contains("FIX");

        workbench.editors().clickBulb();

        assertThat(dialogs.offered).singleElement().asList().contains("create field total in B");
    }

    @Test
    void theSelectedStatementsAreExtractedIntoAMethodNamedAsSuggested() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 7, 9);
        workbench.select(7, 9, 7, 21); // return x + p;
        dialogs.fill(Map.of());

        workbench.act("extract-method");

        var form = dialogs.filled.getFirst();
        assertThat(form.fields()).extracting(f -> f.param().name() + "=" + f.value())
                .containsExactly("method=a.A#m(int)", "lines=7-7", "name=extracted");
        assertThat(workbench.text("a/A.java")).contains("return extracted(p);");
    }

    @Test
    void changeSignatureStartsWithTheParametersTheMethodHas() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 6, 9);

        workbench.act("change-signature");

        assertThat(dialogs.filled.getFirst().fields()).extracting(f -> f.param().name() + "=" + f.value())
                .containsExactly("method=a.A#m(int)", "parameters=int p", "visibility=package");
    }

    @Test
    void theSelectionWidensToWhatItIsInsideAndShrinksBack() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 7, 16); // return x + p: on x

        workbench.act("extend-selection");
        assertThat(workbench.selected()).isEqualTo("x");
        workbench.act("extend-selection");
        assertThat(workbench.selected()).isEqualTo("x + p");
        workbench.act("extend-selection");
        assertThat(workbench.selected()).isEqualTo("return x + p;");
        workbench.act("shrink-selection");
        assertThat(workbench.selected()).isEqualTo("x + p");
    }

    @Test
    void theMenusAreLaidOutAsIntelliJs() throws IOException {
        var bar = workbench().menus();

        var names = new java.util.ArrayList<String>();
        for (var i = 0; i < bar.getMenuCount(); i++) names.add(bar.getMenu(i).getText());
        assertThat(names).containsExactly("File", "Edit", "View", "Navigate", "Code", "Refactor", "Build", "Run", "Help");
        var refactor = bar.getMenu(5);
        var items = new java.util.ArrayList<String>();
        for (var i = 0; i < refactor.getItemCount(); i++) if (refactor.getItem(i) != null) items.add(refactor.getItem(i).getText());
        assertThat(items).startsWith("Refactor This...", "Rename...", "Change Signature...").contains("Extract Method...", "More Refactorings");
    }

    @Test
    void whatIsTypedIsCheckedAsItIsTypedWithoutApplyingIt() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 5, 16);
        workbench.type("a/B.java", B.replace("return a.m(1);", "return total;"));

        workbench.check();

        assertThat(workbench.views().problems()).singleElement().asString().startsWith("a/B.java:5: total cannot be resolved");
        assertThat(workbench.marks()).containsEntry("a/B.java", "ERROR");
        assertThat(workbench.text("a/B.java")).contains("return total;");
        assertThat(workbench.views().changes()).isEmpty();
    }

    @Test
    void aSelectedExpressionIsExtractedIntoAMethodThatAnswersIt() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 7, 16);
        workbench.select(7, 16, 7, 20); // x + p
        dialogs.fill(Map.of("name", "sum"));

        workbench.act("extract-method");

        assertThat(dialogs.filled.getFirst().fields()).extracting(f -> f.param().name() + "=" + f.value()).contains("lines=7:16-7:20");
        assertThat(workbench.text("a/A.java")).contains("return sum(p);", "private int sum(int p) {");
    }

    @Test
    void theNextProblemIsWhereItIsOnItsLine() throws IOException {
        var workbench = workbench();
        workbench.open("a/B.java", 1, 1);
        workbench.type("a/B.java", B.replace("return a.m(1);", "return total + count;"));
        workbench.act("apply-edit");

        workbench.act("next-problem");
        assertThat(workbench.where()).isEqualTo("a/B.java:5:16");
        workbench.act("next-problem");
        assertThat(workbench.where()).isEqualTo("a/B.java:5:24");
        workbench.act("previous-problem");
        assertThat(workbench.where()).isEqualTo("a/B.java:5:16");
    }

    @Test
    void aSelectedExpressionBecomesAConstant() throws IOException {
        var workbench = workbench();
        workbench.open("a/A.java", 7, 16);
        workbench.type("a/A.java", A.replace("return x + p;", "return x + p * (2 + 3);"));
        workbench.act("apply-edit");
        workbench.select(7, 25, 7, 29); // 2 + 3
        dialogs.fill(Map.of("name", "FIVE"));

        workbench.act("introduce-constant");

        assertThat(workbench.text("a/A.java")).contains("private static final int FIVE = 2 + 3;", "return x + p * (FIVE);");
    }

    /**
     * A plan seen again in the window, step by step: first what will run and the place of the code it takes, then
     * what it changed, as a diff; back the same way; and going on from a step brings the files back to it.
     */
    @Test
    void aPlanIsSeenAgainStepByStepBothWays() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/A.java"), A);
        var workspace = Workspace.open(root);
        workspace.capabilities().invoke("plan-new", Map.of("title", "Tidy A", "tasks", "Rename"));
        workspace.capabilities().invoke("task-update", Map.of("task", "t1", "state", "doing"));
        workspace.invoke("rename", Map.of("element", "a.A#m(int)", "name", "plus"));
        workspace.invoke("rename", Map.of("element", "a.A#x", "name", "count"));
        var workbench = new Workbench(new Ide(workspace), dialogs);

        workbench.act("replay");
        assertThat(workbench.viewsMaximized()).isTrue(); // the replay takes the whole window
        var replay = workbench.replay();
        assertThat(replay.steps()).containsExactly("1 rename  element=a.A#m(int)  name=plus  [t1]", "2 rename  element=a.A#x  name=count  [t1]");
        // a step chosen shows before and after side by side at once, what it takes marked on the left
        assertThat(replay.where()).isEqualTo("step 1 of 2: before and after");
        assertThat(replay.header()).isEqualTo("rename: element a.A#m(int), name plus   \u00B7 task t1");
        assertThat(replay.shown()).contains("a/A.java changed*:    int m(int p) { | changed:    int plus(int p) {");
        assertThat(replay.seen()).isEqualTo("a/A.java:5"); // what it takes is what is seen first
        assertThat(replay.files()).containsExactly("a/A.java");

        replay.only("before"); // the file as it was, alone, what the step takes marked: chosen, it stays chosen
        assertThat(replay.where()).isEqualTo("step 1 of 2: before");
        assertThat(replay.shown()).contains("int m(int p) {");
        assertThat(replay.marked()).containsExactly(6, 7, 8);
        replay.next();
        assertThat(replay.where()).isEqualTo("step 2 of 2: before");
        replay.only("both");
        replay.previous();
        assertThat(replay.where()).isEqualTo("step 1 of 2: before and after");

        workbench.act("maximize-views");
        assertThat(workbench.viewsMaximized()).isFalse();
        workbench.open("a/A.java", 1, 1);
        replay.goOn();
        assertThat(workbench.text("a/A.java")).contains("int plus(int p)", "public int x;");
        assertThat(replay.steps()).last().asString().endsWith("(discarded)");
    }
}
