package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IntelliJ's Introduce Constant, composed of primitives: the expression goes to a method, a constant starts with a
 * call of it, the place reads the constant, the call is inlined and the method goes.
 */
class IntroduceConstantTest {

    private static final String PRICES = """
            package a;

            class Prices {
                double withTax(double net) {
                    return net * (1 + 21 / 100.0);
                }
            }
            """;

    @Test
    void anExpressionBecomesAConstantThePlaceReads() {
        var ws = Workspace.of(Map.of("a/Prices.java", PRICES));

        var result = ws.invoke("introduce-constant", Map.of("method", "a.Prices#withTax(double)", "lines", "5:23-5:36", "name", "TAX"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Prices.java")).isEqualTo("""
                package a;

                class Prices {
                    private static final double TAX = 1 + 21 / 100.0;

                    double withTax(double net) {
                        return net * (TAX);
                    }
                }
                """);
    }

    @Test
    void anExpressionThatNeedsTheObjectOrALocalIsNoConstant() {
        var ws = Workspace.of(Map.of("a/Prices.java", PRICES.replace("21 / 100.0", "net / 100.0")));

        assertThat(ws.invoke("introduce-constant", Map.of("method", "a.Prices#withTax(double)", "lines", "5:23-5:36", "name", "TAX")).applied())
                .isFalse();
        assertThat(ws.source("a/Prices.java")).isEqualTo(PRICES.replace("21 / 100.0", "net / 100.0"));
    }
}
