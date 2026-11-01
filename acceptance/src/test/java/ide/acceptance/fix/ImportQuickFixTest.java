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
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ecj.UndefinedType", "a/U.java", 4, "List"));
        assertThat(ws.fixes("a/U.java", 4)).containsExactly("import java.util.List");

        var result = ws.fix("a/U.java", 4, "import java.util.List");

        assertThat(result.applied()).isTrue();
        assertThat(ws.errors()).isEmpty();
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

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/U.java")).isEqualTo("""
                package a;

                import java.util.List;
                import b.Money;

                class U {
                    List<Money> prices;
                }
                """);
    }

    /** Importing a type the file cannot access would leave an error: the fix opens it first, deduced backward. */
    @Test
    void aTypeTheFileCannotAccessIsOpenedBeforeItIsImported() {
        var ws = Workspace.of(Map.of(
                "b/Money.java", """
                        package b;

                        class Money {
                        }
                        """,
                "a/U.java", """
                        package a;

                        class U {
                            Money price;
                        }
                        """));

        assertThat(ws.fixes("a/U.java", 4)).containsExactly("open-visibility b.Money package -> public, then import b.Money");
        assertThat(ws.why("a/U.java", 4).lines()).containsExactly(
                "ecj.UndefinedType Money",
                "? resolves(Money)",
                "  ! import b.Money",
                "    ? accessible(a.U, b.Money)",
                "      ! open-visibility b.Money package -> public",
                "  X use-similar: nonempty(unresolved-in(path, symbol, \"value\"))",
                "  X call-similar: nonempty(unresolved-in(path, symbol, \"call\"))",
                "  X create-field: nonempty(scope(unresolved-in(path, symbol, \"value\")))",
                "  X create-method: nonempty(scope(unresolved-in(path, symbol, \"call\")))");

        ws.fix("a/U.java", 4, "open-visibility b.Money package -> public, then import b.Money");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("b/Money.java")).contains("public class Money {");
        assertThat(ws.source("a/U.java")).contains("import b.Money;");
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
                "ecj.UndefinedType List",
                "? resolves(List)",
                "  ! import java.util.List",
                "  X use-similar: nonempty(unresolved-in(path, symbol, \"value\"))",
                "  X call-similar: nonempty(unresolved-in(path, symbol, \"call\"))",
                "  X create-field: nonempty(scope(unresolved-in(path, symbol, \"value\")))",
                "  X create-method: nonempty(scope(unresolved-in(path, symbol, \"call\")))");
    }
}
