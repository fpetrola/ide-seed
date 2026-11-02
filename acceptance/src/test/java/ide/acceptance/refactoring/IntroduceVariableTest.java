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

    /** An expression that reads a local of its method: the local's value reads it too. */
    @Test
    void anExpressionThatReadsALocal() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                import java.util.List;

                class Rows {
                    void print(String line) {
                        List<String> fields = List.of(line.split(","));
                        System.out.println("dni " + fields.get(3) + ".");
                    }
                }
                """));

        var result = ws.invoke("introduce-variable", Map.of("method", "a.Rows#print(String)", "lines", "8:37-8:49", "name", "dni"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                        List<String> fields = List.of(line.split(","));
                        String dni = fields.get(3);
                        System.out.println("dni " + dni + ".");
                """).doesNotContain("computeDni");
        assertThat(ws.errors()).isEmpty();
    }

    /** An expression in a loop and a branch: the local is declared right before its statement there, in the same block. */
    @Test
    void inALoopTheLocalIsDeclaredInTheBlockOfItsStatement() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                import java.util.List;

                class Rows {
                    void print(List<String> lines) {
                        for (String line : lines) {
                            List<String> fields = List.of(line.split(","));
                            if (fields.get(0).startsWith("C")) {
                                System.out.println("dni " + fields.get(3) + ".");
                            }
                        }
                    }
                }
                """));

        var result = ws.invoke("introduce-variable", Map.of("method", "a.Rows#print(List<String>)", "lines", "10:45-10:57", "name", "dni"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                            if (fields.get(0).startsWith("C")) {
                                String dni = fields.get(3);
                                System.out.println("dni " + dni + ".");
                            }
                """).doesNotContain("computeDni");
        assertThat(ws.errors()).isEmpty();
    }

    /** A statement of a branch that is no block: there is no place before it for a local, refused as it was. */
    @Test
    void aBranchThatIsNoBlockIsRefused() {
        var source = """
                package a;

                class Rows {
                    void print(String line) {
                        if (line.isEmpty()) System.out.println("dni " + line.trim());
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Rows.java", source));

        var result = ws.invoke("introduce-variable", Map.of("method", "a.Rows#print(String)", "lines", "5:57-5:67", "name", "dni"));

        assertThat(result.applied()).isFalse();
        assertThat(ws.source("a/Rows.java")).isEqualTo(source);
    }

    /**
     * Operands of a concatenation next to each other, from inside a literal: the literal is split there and they are
     * grouped, as IntelliJ's, so they are an expression of their own.
     */
    @Test
    void operandsOfAConcatenationFromInsideALiteral() {
        var ws = Workspace.of(Map.of("a/Rows.java", """
                package a;

                class Rows {
                    String insert(int id, String name, String street) {
                        return "VALUES (" + id + ",'address of " + name + "', '" + street + "')";
                    }
                }
                """));

        var result = ws.invoke("introduce-variable", Map.of("method", "a.Rows#insert(int,String,String)", "lines", "5:37-5:55", "name", "description"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("""
                        String description = "address of " + name;
                        return "VALUES (" + id + ",'" + description + "', '" + street + "')";
                """);
        assertThat(ws.errors()).isEmpty();
    }

    /** Grouping numbers that were added to a String would add them first: refused. */
    @Test
    void operandsThatWouldBeAddedAsNumbersAreRefused() {
        var source = """
                package a;

                class Rows {
                    String insert(int id, int n) {
                        return "VALUES " + id + n;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Rows.java", source));

        var result = ws.invoke("introduce-variable", Map.of("method", "a.Rows#insert(int,int)", "lines", "5:28-5:33", "name", "sum"));

        assertThat(result.applied()).isFalse();
        assertThat(ws.source("a/Rows.java")).isEqualTo(source);
    }
}
