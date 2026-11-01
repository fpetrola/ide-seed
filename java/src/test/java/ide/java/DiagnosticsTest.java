package ide.java;

import ide.projection.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every error the compiler finds, as an IDE shows them all: one that does not resolve does not hide the others. */
class DiagnosticsTest {

    @Test
    void anUnresolvedNameDoesNotHideAFinalFieldLeftUnset() {
        var projection = JavaProjection.of(Map.of(
                "a/Broken.java", "package a;\n\nclass Broken {\n    int total() {\n        return count;\n    }\n}\n",
                "a/Unset.java", "package a;\n\nclass Unset {\n    private final int limit;\n}\n"));

        assertThat(projection.diagnostics().stream().filter(Diagnostic::isError)).extracting(Diagnostic::path, Diagnostic::line).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("a/Broken.java", 5), org.assertj.core.groups.Tuple.tuple("a/Unset.java", 4));
    }

    @Test
    void whatAnEditorSaysIsCheckedWithoutTouchingTheProgram() {
        var projection = JavaProjection.of(Map.of("a/A.java", "package a;\n\nclass A {\n    int x;\n}\n"));
        var program = projection.program();

        var typed = projection.diagnostics("a/A.java", "package a;\n\nclass A {\n    int x = y\n}\n");

        assertThat(typed).extracting(d -> d.code() + " " + d.line()).contains("ecj.ParsingErrorInsertToComplete 4");
        assertThat(projection.program()).isSameAs(program);
        assertThat(projection.diagnostics()).isEmpty();
    }
}
