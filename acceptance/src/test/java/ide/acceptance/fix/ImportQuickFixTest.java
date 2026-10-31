package ide.acceptance.fix;

import ide.projection.Diagnostic;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportQuickFixTest {

    @Test
    void unresolvedJdkTypeIsFixedByImportingIt() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    List<String> names() {
                        return null;
                    }
                }
                """));

        assertThat(ws.diagnostics())
                .extracting(Diagnostic::code, Diagnostic::path, Diagnostic::line, Diagnostic::symbol)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("javac.cant.resolve.location", "a/U.java", 4, "List"));
        assertThat(ws.fixes("a/U.java", 4)).containsExactly("import java.util.List");

        var result = ws.fix("a/U.java", 4, "import java.util.List");

        assertThat(result.applied()).isTrue();
        assertThat(ws.diagnostics()).isEmpty();
        assertThat(ws.source("a/U.java")).isEqualTo("""
                package a;

                import java.util.List;

                class U {
                    List<String> names() {
                        return null;
                    }
                }
                """);
    }

    @Test
    void unresolvedWorkspaceTypeIsImportedAfterExistingImports() {
        var ws = Workspace.of(Map.of(
                "b/Money.java", """
                        package b;

                        public class Money {
                        }
                        """,
                "a/U.java", """
                        package a;

                        import java.util.List;

                        class U {
                            List<Money> prices;
                        }
                        """));

        assertThat(ws.fixes("a/U.java", 6)).containsExactly("import b.Money");

        ws.fix("a/U.java", 6, "import b.Money");

        assertThat(ws.diagnostics()).isEmpty();
        assertThat(ws.source("a/U.java")).isEqualTo("""
                package a;

                import java.util.List;
                import b.Money;

                class U {
                    List<Money> prices;
                }
                """);
    }

    @Test
    void whyShowsHowTheFixWasDeduced() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    List<String> names;
                    int count;
                }
                """));

        assertThat(ws.why("a/U.java", 4).lines()).containsExactly(
                "javac.cant.resolve.location List",
                "? resolves(List)",
                "  ! import java.util.List",
                "  X create-field: not(is-type-name(symbol))");
    }
}
