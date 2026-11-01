package ide.session;

import ide.java.JavaProjection;
import ide.knowledge.Knowledge;
import ide.refactoring.Library;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fixes of a line, found alone: a project, and the knowledge that deduces them from its diagnostics. */
class FixesTest {

    private static final String P = """
            package a;

            class P {
                int twice() {
                    return total * 2;
                }
            }
            """;

    @Test
    void theFixesOfALineAreDeducedFromItsDiagnostics() {
        var fixes = new Fixes(JavaProjection.of(Map.of("a/P.java", P)), Knowledge.standard(Library.standard()));

        assertThat(fixes.titles("a/P.java", 5)).containsExactly("create field total in P");
        assertThat(fixes.titles("a/P.java", 4)).isEmpty();
        assertThat(fixes.asks("a/P.java", 5, "create field total in P")).containsExactly("type: type of the new field [int]");
        assertThat(fixes.find("a/P.java", 5, "create field total in P").diagnostic().symbol()).isEqualTo("total");
        assertThatThrownBy(() -> fixes.find("a/P.java", 5, "rename total")).hasMessage("no fix 'rename total' at a/P.java:5");
        assertThat(fixes.why("a/P.java", 5).lines()).containsExactly(
                "javac.cant.resolve.location total",
                "? resolves(total)",
                "  X import: nonempty(unresolved-in(path, symbol, \"type\"))",
                "  X call-similar: nonempty(unresolved-in(path, symbol, \"call\"))",
                "  ! create field total in P");
    }
}
