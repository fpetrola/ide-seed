package ide.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinalFieldQuickFixTest {

    private static final String P = """
            package a;

            class P {
                private final int limit;
                private final String label;

                int twice() {
                    return limit * 2;
                }
            }
            """;

    @Test
    void uninitializedFinalOffersAnInitializerOrDroppingFinal() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        assertThat(ws.fixes("a/P.java", 4)).containsExactly("initialize limit in its declaration", "make limit not final");
        assertThat(ws.asks("a/P.java", 4, "initialize limit in its declaration")).containsExactly("value: initial value [0]");
        assertThat(ws.asks("a/P.java", 5, "initialize label in its declaration")).containsExactly("value: initial value [null]");

        ws.fix("a/P.java", 4, "initialize limit in its declaration", Map.of("value", "10"));
        ws.fix("a/P.java", 5, "make label not final");

        assertThat(ws.source("a/P.java")).contains("private final int limit = 10;", "private String label;");
        assertThat(ws.diagnostics()).isEmpty();
    }
}
