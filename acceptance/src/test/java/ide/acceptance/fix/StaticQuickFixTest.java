package ide.acceptance.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static methods from what Java says of them (java.rules): a static method uses an instance member of its own object
 * only through an object, what is used through a type is static, and a field's static is a promise. The fixes of a
 * static place calling what needs an object are deduced, with what each breaks mended in the same fix.
 */
class StaticQuickFixTest {

    private static final String PRICES = """
            package a;

            class Prices {
                double rate = 0.21;

                double gross(double net) {
                    return net * factor();
                }

                double factor() {
                    return 1 + rate;
                }

                static double check(double net) {
                    return gross(net);
                }
            }
            """;

    @Test
    void makingTheCalleeStaticWouldEndInAFieldSoTheCallerStopsBeingStatic() {
        var ws = Workspace.of(Map.of("a/Prices.java", PRICES));

        assertThat(ws.fixes("a/Prices.java", 15)).containsExactly("make check() not static");
        assertThat(ws.why("a/Prices.java", 15).lines()).containsSubsequence(
                "? no-object(a.Prices#check(double), a.Prices#gross(double))",
                "  X make gross() static, then make factor() static, then make rate static: promise static of a.Prices#rate: one value for all objects, or one for each",
                "  ! make check() not static");

        ws.fix("a/Prices.java", 15, "make check() not static");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Prices.java")).contains("    double check(double net) {");
    }

    @Test
    void whenNothingStopsItWhatTheCalleeCallsBecomesStaticToo() {
        var ws = Workspace.of(Map.of("a/Prices.java", PRICES.replace("return 1 + rate;", "return 1.21;")));

        assertThat(ws.fixes("a/Prices.java", 15)).containsExactly(
                "make check() not static", "make gross() static, then make factor() static");

        ws.fix("a/Prices.java", 15, "make gross() static, then make factor() static");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Prices.java")).contains("static double gross(double net) {", "static double factor() {",
                "static double check(double net) {");
    }

    @Test
    void aCallerThatGoesThroughTheTypeKeepsTheMethodStatic() {
        var ws = Workspace.of(Map.of("a/Prices.java", PRICES.replace("return 1 + rate;", "return 1.21;"), "a/Shop.java", """
                package a;

                class Shop {
                    double sell(double net) {
                        return Prices.check(net);
                    }
                }
                """));

        assertThat(ws.fixes("a/Prices.java", 15)).containsExactly("make gross() static, then make factor() static");
    }
}
