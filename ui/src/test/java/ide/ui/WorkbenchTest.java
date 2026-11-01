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
