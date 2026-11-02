package ide.ui;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                    .contains("claude --mcp-config \"$IDESEED_MCP_CONFIG\"");
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

    /** The plan an agent (or anyone) sets and follows is shown beside the project, with how far it got. */
    @Test
    void thePlanBeingFollowedIsShownBesideTheProject() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/A.java"), A);
        var workspace = Workspace.open(root);
        var workbench = new Workbench(new Ide(workspace), dialogs);

        assertThat(workbench.plan().lines()).containsExactly("No plan");

        workspace.capabilities().invoke("plan-set", Map.of("title", "Encapsulate A", "tasks", "Find its public fields\nEncapsulate x"));
        workspace.capabilities().invoke("task", Map.of("n", "1", "state", "done", "note", "only x"));
        workspace.capabilities().invoke("task", Map.of("n", "2", "state", "doing"));
        workbench.refresh();

        assertThat(workbench.plan().lines()).containsExactly("Encapsulate A (1 of 2 done)",
                "1. [done] Find its public fields - only x", "2. [doing] Encapsulate x");
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

        var center = (javax.swing.JSplitPane) java.util.Arrays.stream(workbench.getComponents())
                .filter(javax.swing.JSplitPane.class::isInstance).map(javax.swing.JSplitPane.class::cast)
                .findFirst().orElseThrow().getRightComponent();
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
                .containsExactly("method=a.A#m(int)", "parameters=int p");
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
        assertThat(names).containsExactly("File", "Edit", "View", "Navigate", "Code", "Refactor", "Build", "Help");
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
}
