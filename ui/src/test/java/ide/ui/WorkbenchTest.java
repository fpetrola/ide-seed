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

        assertThat(workbench.views().problems()).singleElement().asString().startsWith("a/B.java:5: cannot find symbol");
        dialogs.choose("create field total").line("int");

        workbench.act("quick-fix");

        assertThat(workbench.views().problems()).isEmpty();
        assertThat(workbench.text("a/B.java")).contains("int total;");
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

        assertThat(dialogs.offered.getFirst()).contains("rename", "encapsulate-field", "pull-up-field")
                .doesNotContain("extract-superclass", "pull-up-method", "ls");
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
}
