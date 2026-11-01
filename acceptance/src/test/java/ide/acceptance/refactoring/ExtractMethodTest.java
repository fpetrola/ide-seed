package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractMethodTest {

    private static final String INVOICE = """
            package a;

            class Invoice {
                double rate;

                double total(double net, int qty) {
                    double gross = net * qty;
                    double tax = gross * rate;
                    double sum = gross + tax;
                    if (sum < 0) {
                        return 0;
                    }
                    return sum;
                }
            }
            """;

    @Test
    void extractsStatementsWithTheirInputsAsParametersAndTheirOutputAsResult() {
        var ws = Workspace.of(Map.of("a/Invoice.java", INVOICE));

        var result = ws.invoke("extract-method", Map.of("method", "a.Invoice#total(double,int)", "lines", "8-9", "name", "withTax"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Invoice.java")).isEqualTo("""
                package a;

                class Invoice {
                    double rate;

                    double total(double net, int qty) {
                        double gross = net * qty;
                        double sum = withTax(gross);
                        if (sum < 0) {
                            return 0;
                        }
                        return sum;
                    }

                    private double withTax(double gross) {
                        double tax = gross * rate;
                        double sum = gross + tax;
                        return sum;
                    }
                }
                """);
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void aFragmentWithAJumpOutOfItIsRefused() {
        var ws = Workspace.of(Map.of("a/Invoice.java", INVOICE));

        var result = ws.invoke("extract-method", Map.of("method", "a.Invoice#total(double,int)", "lines", "10-12", "name", "clamp"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("extract.jump");
    }

    @Test
    void aFragmentWithTwoValuesUsedAfterwardsIsRefused() {
        var ws = Workspace.of(Map.of("a/Invoice.java", INVOICE.replace("double sum = gross + tax;", "double sum = gross + tax + 0 * net;")
                .replace("return sum;", "return sum + tax;")));

        var result = ws.invoke("extract-method", Map.of("method", "a.Invoice#total(double,int)", "lines", "8-9", "name", "withTax"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("extract.outputs");
    }
}
