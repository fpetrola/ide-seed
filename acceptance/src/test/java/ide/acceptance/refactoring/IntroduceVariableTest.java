package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An expression becomes a local declared right before the statement it is in, which reads it, as IntelliJ's Introduce
 * Variable: what runs, runs where it ran.
 */
class IntroduceVariableTest {

    @Test
    void anExpressionBecomesALocalDeclaredRightBeforeItsStatement() {
        var ws = Workspace.of(Map.of("a/Report.java", """
                package a;

                import java.util.List;

                class Report {
                    String line(List<String> fields) {
                        System.out.println("start");
                        return "address of " + fields.get(1);
                    }
                }
                """));

        var result = ws.invoke("introduce-variable", Map.of("method", "a.Report#line(List<String>)", "lines", "8:16-8:44", "name", "address"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Report.java")).contains("""
                    String line(List<String> fields) {
                        System.out.println("start");
                        String address = "address of " + fields.get(1);
                        return address;
                    }
                """);
        assertThat(ws.source("a/Report.java")).doesNotContain("computeAddress");
        assertThat(ws.errors()).isEmpty();
    }

    /** What the expression reads is declared before the statement: the local goes after it, right before the statement. */
    @Test
    void theLocalComesAfterWhatItsValueReads() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                import java.util.List;

                class Rows {
                    void print(List<String> fields) {
                        String name = fields.get(2);
                        System.out.println("address of " + name);
                    }
                }
                """));

        var result = ws.invoke("introduce-variable", Map.of("method", "a.Rows#print(List<String>)", "lines", "8:28-8:47", "name", "label"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                        String name = fields.get(2);
                        String label = "address of " + name;
                        System.out.println(label);
                """);
        assertThat(ws.errors()).isEmpty();
    }
}
