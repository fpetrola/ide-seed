package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A method takes what one of its calls does as an object of a new functional interface, as IntelliJ's Introduce
 * Functional Parameter: the interface declares the called method's signature, and the callers pass the method itself.
 */
class IntroduceFunctionalParameterTest {

    private static final String ROWS = """
        package a;

        import java.util.List;

        class Rows {
            static void processRow(List<String> fields) throws Exception {
                System.out.println(fields);
            }

            static void forEachRow() throws Exception {
                for (var line : List.of("a,b", "c,d")) {
                    List<String> fields = List.of(line.split(","));
                    processRow(fields);
                }
            }

            public static void main(String[] args) throws Exception {
                forEachRow();
            }
        }
        """;

    @Test
    void theCallIsMadeOnWhatTheCallersPass() {
        var ws = Workspace.of(Map.of("a/Rows.java", ROWS));

        var result = ws.invoke("introduce-functional-parameter", Map.of("method", "a.Rows#forEachRow()",
                "called", "a.Rows#processRow(List<String>)", "name", "RowProcessor", "parameter", "rowProcessor"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/RowProcessor.java")).contains("@FunctionalInterface", "interface RowProcessor",
                "void processRow(List<String> fields) throws Exception;");
        assertThat(ws.source("a/Rows.java")).contains("static void forEachRow(RowProcessor rowProcessor) throws Exception {",
                "            rowProcessor.processRow(fields);", "        forEachRow(Rows::processRow);");
        assertThat(ws.errors()).isEmpty();
    }

    /** What the callers pass is the method itself: inlined, it is first the lambda that calls it. */
    @Test
    void theMethodPassedIsInlinedAsALambda() {
        var ws = Workspace.of(Map.of("a/Rows.java", ROWS));
        assertThat(ws.invoke("introduce-functional-parameter", Map.of("method", "a.Rows#forEachRow()",
                "called", "a.Rows#processRow(List<String>)", "name", "RowProcessor", "parameter", "rowProcessor")).violations()).isEmpty();

        var result = ws.invoke("inline-method", Map.of("method", "a.Rows#processRow(List<String>)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("forEachRow((fields) -> {").doesNotContain("processRow(List<String> fields)");
        assertThat(ws.source("a/RowProcessor.java")).contains("import java.util.List;\n\n@FunctionalInterface");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void inTheDefaultPackageTheImportsStandApart() {
        var ws = Workspace.of(Map.of("Rows.java", ROWS.replace("package a;\n\n", "")));

        assertThat(ws.invoke("introduce-functional-parameter", Map.of("method", "Rows#forEachRow()",
                "called", "Rows#processRow(List<String>)", "name", "RowProcessor", "parameter", "rowProcessor")).violations()).isEmpty();

        assertThat(ws.source("RowProcessor.java")).startsWith("import java.util.List;\n\n@FunctionalInterface\npublic interface RowProcessor {");
    }
}
