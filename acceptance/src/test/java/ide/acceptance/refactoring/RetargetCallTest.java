package ide.acceptance.refactoring;

import ide.core.Json;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A call found by its id among an element's uses, pointed at a method of another type: it is made on the one variable
 * of that type there, as when a method takes the functional parameter it is to call.
 */
class RetargetCallTest {

    @Test
    void aCallRetargetedToAMethodOfAParametersTypeIsMadeOnIt() {
        var ws = Workspace.of(Map.of("a/RowProcessor.java", """
                package a;

                interface RowProcessor {
                    void processRow(String row);
                }
                """, "a/Rows.java", """
                package a;

                class Rows {
                    static void processRow(String row) {
                        System.out.println(row);
                    }

                    static void forEachRow(RowProcessor rowProcessor) {
                        processRow("x");
                    }

                    public static void main(String[] args) {
                        forEachRow(Rows::processRow);
                    }
                }
                """));

        @SuppressWarnings("unchecked") var uses = (List<Map<String, String>>) Json.read(ws.capabilities().invoke("uses", Map.of("element", "a.Rows#processRow(String)")));
        var call = uses.stream().filter(use -> use.get("site").equals("a.Rows#forEachRow(RowProcessor)")).findFirst().orElseThrow();
        var result = ws.invoke("retarget", Map.of("reference", call.get("reference"), "target", "a.RowProcessor#processRow(String)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Rows.java")).contains("        rowProcessor.processRow(\"x\");", "forEachRow(Rows::processRow);");
        assertThat(ws.errors()).isEmpty();
    }
}
