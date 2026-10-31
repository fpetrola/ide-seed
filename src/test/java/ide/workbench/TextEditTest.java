package ide.workbench;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TextEditTest {

    private static final String P = """
            package a;

            class P {
                int x;
            }
            """;

    @Test
    void anEditedFileJoinsTheModelAndLaterRefactoringsSeeIt() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var edit = ws.edit("a/P.java", P.replace("    int x;\n", "    int x;\n\n    int twice() {\n        return x * 2;\n    }\n"));
        ws.invoke("rename", Map.of("element", "a.P#x", "name", "value"));

        assertThat(edit.applied()).isTrue();
        assertThat(ws.source("a/P.java")).contains("int value;", "return value * 2;");
    }

    @Test
    void editsAreUndoableLikeAnyTransaction() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        ws.edit("a/P.java", P.replace("int x;", "int x, y;"));
        ws.undo();

        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }

    @Test
    void unparsableTextIsRefusedAndTheModelKeepsTheLastGoodVersion() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var edit = ws.edit("a/P.java", P.replace("int x;", "int x"));

        assertThat(edit.applied()).isFalse();
        assertThat(edit.violations().getFirst().code()).isEqualTo("projection.unparsable");
        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }
}
