package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A local is replaced by the value it is given wherever it is read, and goes. What would change what the program does
 * without changing what any name binds is refused by the driver: a local given another value, a value with effects or
 * of another type, one read after what it reads changes, or later, in a lambda. A name the value would capture is the
 * verifier's to refuse.
 */
class InlineVariableTest {

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

    private static void refused(String source, String local, String code) {
        var ws = Workspace.of(Map.of("a/A.java", source));

        var result = ws.invoke("inline-variable", Map.of("local", local));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).contains(code);
        assertThat(ws.source("a/A.java")).isEqualTo(source);
    }

    @Test
    void eachUseOfALocalBecomesItsValueAndTheLocalGoes() {
        var ws = Workspace.of(Map.of("a/Invoice.java", INVOICE));

        var result = ws.invoke("inline-variable", Map.of("local", "a.Invoice#total(double,int)/sum"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Invoice.java")).isEqualTo("""
                package a;

                class Invoice {
                    double rate;

                    double total(double net, int qty) {
                        double gross = net * qty;
                        double tax = gross * rate;
                        if (gross + tax < 0) {
                            return 0;
                        }
                        return gross + tax;
                    }
                }
                """);
    }

    @Test
    void aValueGetsParenthesesWhereItsUseNeedsThem() {
        var ws = Workspace.of(Map.of("a/A.java", """
                package a;

                class A {
                    int net(int gross, int tax) {
                        int rest = gross - tax;
                        return 2 * rest;
                    }
                }
                """));

        var result = ws.invoke("inline-variable", Map.of("local", "a.A#net(int,int)/rest"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/A.java")).contains("        return 2 * (gross - tax);\n").doesNotContain("rest");
    }

    @Test
    void aLocalGivenAnotherValueAfterwardsStays() {
        refused("""
                package a;

                class A {
                    int up(int n) {
                        int c = n;
                        c += 1;
                        return c;
                    }
                }
                """, "a.A#up(int)/c", "inline.reassigned");
    }

    @Test
    void aValueWithEffectsStays() {
        refused("""
                package a;

                class A {
                    int count;

                    int twice() {
                        int c = next();
                        return c + c;
                    }

                    int next() {
                        return ++count;
                    }
                }
                """, "a.A#twice()/c", "inline.effects");
    }

    @Test
    void aValueReadAfterWhatItReadsChangesStays() {
        refused("""
                package a;

                class A {
                    int step(int n) {
                        int was = n;
                        n = n + 1;
                        return was * n;
                    }
                }
                """, "a.A#step(int)/was", "inline.changed");
    }

    @Test
    void aValueReadInALoopThatChangesWhatItReadsStays() {
        refused("""
                package a;

                class A {
                    int sum(int n) {
                        int first = n;
                        int total = 0;
                        while (n > 0) {
                            total += first;
                            n--;
                        }
                        return total;
                    }
                }
                """, "a.A#sum(int)/first", "inline.changed");
    }

    @Test
    void aFieldTheValueReadsMayChangeInACallBetween() {
        refused("""
                package a;

                class A {
                    int size;

                    int grown() {
                        int was = size;
                        grow();
                        return was;
                    }

                    void grow() {
                        size++;
                    }
                }
                """, "a.A#grown()/was", "inline.changed");
    }

    @Test
    void aValueOfAnotherTypeThanItsLocalStays() {
        refused("""
                package a;

                class A {
                    double half(int count) {
                        double all = count;
                        return all / 2;
                    }
                }
                """, "a.A#half(int)/all", "inline.type");
    }

    @Test
    void aValueReadLaterInALambdaStays() {
        refused("""
                package a;

                import java.util.function.IntSupplier;

                class A {
                    IntSupplier later(int n) {
                        int was = n;
                        return () -> was;
                    }
                }
                """, "a.A#later(int)/was", "inline.deferred");
    }

    @Test
    void aNameThatWouldCaptureTheValueIsTheVerifiersToRefuse() {
        refused("""
                package a;

                class A {
                    int size;

                    int area(int[] sides) {
                        int s = size;
                        int total = 0;
                        for (int size : sides) total += size * s;
                        return total;
                    }
                }
                """, "a.A#area(int[])/s", "binding.rebound");
    }
}
