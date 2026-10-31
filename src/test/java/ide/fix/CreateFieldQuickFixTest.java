package ide.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreateFieldQuickFixTest {

    private static final String U = """
            package a;

            class U {
                long total() {
                    count += 1;
                    return count;
                }
            }
            """;

    @Test
    void unresolvedVariableOffersToCreateAFieldAskingItsType() {
        var ws = Workspace.of(Map.of("a/U.java", U));

        assertThat(ws.fixes("a/U.java", 5)).containsExactly("create field count");
        assertThat(ws.asks("a/U.java", 5, "create field count")).containsExactly("type: type of the new field [int]");

        var result = ws.fix("a/U.java", 5, "create field count", Map.of("type", "long"));

        assertThat(result.applied()).isTrue();
        assertThat(ws.diagnostics()).isEmpty();
        assertThat(ws.source("a/U.java")).isEqualTo("""
                package a;

                class U {
                    long count;

                    long total() {
                        count += 1;
                        return count;
                    }
                }
                """);
    }

    @Test
    void withoutAnswersTheDefaultIsUsed() {
        var ws = Workspace.of(Map.of("a/U.java", U));

        ws.fix("a/U.java", 5, "create field count");

        assertThat(ws.source("a/U.java")).contains("    int count;\n");
    }

    @Test
    void typeNamesAreNotOfferedAsFields() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    List<String> names;
                }
                """));

        assertThat(ws.fixes("a/U.java", 4)).containsExactly("import java.util.List");
    }
}
