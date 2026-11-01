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
    void aFragmentEndingTheMethodWithItsReturnAnswersWhatTheMethodAnswers() {
        var ws = Workspace.of(Map.of("a/Invoice.java", INVOICE));

        var result = ws.invoke("extract-method", Map.of("method", "a.Invoice#total(double,int)", "lines", "9-13", "name", "settled"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Invoice.java")).contains("""
                        double tax = gross * rate;
                        return settled(gross, tax);
                    }
                """, """
                    private double settled(double gross, double tax) {
                        double sum = gross + tax;
                        if (sum < 0) {
                            return 0;
                        }
                        return sum;
                    }
                """);
    }

    @Test
    void anExpressionIsExtractedIntoAMethodThatAnswersIt() {
        var ws = Workspace.of(Map.of("a/Invoice.java", INVOICE));

        var result = ws.invoke("extract-method", Map.of("method", "a.Invoice#total(double,int)", "lines", "8:22-8:33", "name", "taxOf"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Invoice.java")).contains("double tax = taxOf(gross);", """
                    private double taxOf(double gross) {
                        return gross * rate;
                    }
                """);
    }

    @Test
    void aLoopIsExtractedAlthoughTheNextOneReusesTheNameOfItsVariable() {
        var ws = Workspace.of(Map.of("a/Scores.java", """
                package a;

                class Scores {
                    int total(int[] points) {
                        int sum = 0;
                        for (int i = 0; i < points.length; i++) sum += points[i];
                        for (int i = 0; i < points.length; i++) sum -= i;
                        return sum;
                    }
                }
                """));

        var result = ws.invoke("extract-method", Map.of("method", "a.Scores#total(int[])", "lines", "6-6", "name", "add"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Scores.java")).isEqualTo("""
                package a;

                class Scores {
                    int total(int[] points) {
                        int sum = 0;
                        sum = add(points, sum);
                        for (int i = 0; i < points.length; i++) sum -= i;
                        return sum;
                    }

                    private int add(int[] points, int sum) {
                        for (int i = 0; i < points.length; i++) sum += points[i];
                        return sum;
                    }
                }
                """);
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
