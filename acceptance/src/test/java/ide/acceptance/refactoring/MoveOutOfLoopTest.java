package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last statements of a loop's body moved after it: a local of the loop they read is declared before it and given
 * its value in each turn. They run once, after the last turn, with what it left, or with the type's default when there
 * was no turn: it says it changes meaning.
 */
class MoveOutOfLoopTest {

    private static final String ROWS = """
            package a;

            import java.util.List;

            class Rows {
                void row(List<String> names) {
                    for (String each : names) {
                        String name = each.trim();
                        System.out.println("address of " + name);
                        System.out.println(name.length());
                    }
                }
            }
            """;

    @Test
    void theLastStatementsRunAfterTheLoopWithWhatItsLocalLeft() {
        var ws = Workspace.of(Map.of("a/Rows.java", ROWS));

        var result = ws.invoke("move-out-of-loop", Map.of("from", "a.Rows#row(List<String>)[1][2]",
                "locals", List.of("a.Rows#row(List<String>)/name")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                    void row(List<String> names) {
                        String name = null;
                        for (String each : names) {
                            name = each.trim();
                        }
                        System.out.println("address of " + name);
                        System.out.println(name.length());
                    }
                """);
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.capabilities().get("move-out-of-loop").description()).startsWith("Changes meaning:");
    }

    /** A local is created in its method, and moved where it goes after: not in a statement. */
    @Test
    void aLocalCreatedInAStatementIsRefusedSayingWhere() {
        var ws = Workspace.of(Map.of("a/Rows.java", ROWS));

        var result = ws.invoke("create", Map.of("kind", "LOCAL", "parent", "a.Rows#row(List<String>)[1]", "name", "x"));

        assertThat(result.violations()).extracting(Violation::code).containsExactly("create.local");
        assertThat(ws.source("a/Rows.java")).isEqualTo(ROWS);
    }

    /** A local declared in what moves and read after it goes with them, in their order. */
    @Test
    void aLocalDeclaredInWhatMovesGoesWithThem() {
        var source = ROWS.replace("System.out.println(\"address of \" + name);", "String description = \"address of \" + name;")
                .replace("System.out.println(name.length());", "System.out.println(description);");
        var ws = Workspace.of(Map.of("a/Rows.java", source));

        var result = ws.invoke("move-out-of-loop", Map.of("from", "a.Rows#row(List<String>)[1][2]",
                "locals", List.of("a.Rows#row(List<String>)/name")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                        }
                        String description = "address of " + name;
                        System.out.println(description);
                    }
                """);
        assertThat(ws.errors()).isEmpty();
    }

    /** A loop last in an if's block: what moves goes at the end of that block. */
    @Test
    void aLoopLastInABranchLeavesThemAtItsEnd() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                import java.util.List;

                class Rows {
                    void row(List<String> names, boolean any) {
                        if (any) {
                            for (String each : names) {
                                String name = each.trim();
                                System.out.println(name);
                            }
                        }
                    }
                }
                """));

        var result = ws.invoke("move-out-of-loop", Map.of("from", "a.Rows#row(List<String>,boolean)[1][1][2]",
                "locals", List.of("a.Rows#row(List<String>,boolean)/name")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                        if (any) {
                            String name = null;
                            for (String each : names) {
                                name = each.trim();
                            }
                            System.out.println(name);
                        }
                """);
        assertThat(ws.errors()).isEmpty();
    }
}
