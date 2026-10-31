package ide.refactor;

import ide.coherence.Violation;
import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeleteTest {

    private static final String P = """
            package a;

            class P {
                int used;
                int unused;

                // helper, never called
                void helper() {
                }

                int get() {
                    return used;
                }
            }
            """;

    @Test
    void deletesUnusedMembersWithTheirCommentsAndBlankLine() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        assertThat(ws.invoke("safe-delete", Map.of("element", "a.P#helper()")).applied()).isTrue();
        assertThat(ws.invoke("safe-delete", Map.of("element", "a.P#unused")).applied()).isTrue();

        assertThat(ws.source("a/P.java")).isEqualTo("""
                package a;

                class P {
                    int used;

                    int get() {
                        return used;
                    }
                }
                """);
    }

    @Test
    void deletingSomethingInUseAbortsNamingTheUses() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var result = ws.invoke("safe-delete", Map.of("element", "a.P#used"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("delete.referenced");
        assertThat(result.violations().getFirst().detail()).contains("a.P#get()");
        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }
}
