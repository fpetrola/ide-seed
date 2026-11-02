package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChangeSignatureTest {

    private static final String CALC = """
            package a;

            public class Calc {
                public int add(int a, int b, int unused) {
                    return a + b;
                }

                int use() {
                    return add(1, 2, 0);
                }
            }
            """;
    private static final String USER = """
            package b;

            class User {
                int four() {
                    return new a.Calc().add(3, 1, 9) + 0;
                }
            }
            """;

    @Test
    void reordersAddsWithDefaultAndRemovesParametersUpdatingEveryCall() {
        var ws = Workspace.of(Map.of("a/Calc.java", CALC, "b/User.java", USER));

        var result = ws.invoke("change-signature", Map.of("method", "a.Calc#add(int,int,int)", "parameters", "b, a, scale:int=1"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Calc.java")).isEqualTo("""
                package a;

                public class Calc {
                    public int add(int b, int a, int scale) {
                        return a + b;
                    }

                    int use() {
                        return add(2, 1, 1);
                    }
                }
                """);
        assertThat(ws.source("b/User.java")).contains("new a.Calc().add(1, 3, 1) + 0");
        assertThat(ws.errors()).isEmpty();
    }

    /** The new list written as in Java: a kept parameter with its type, a new one with the value calls pass. */
    @Test
    void theParametersCanBeWrittenAsInJava() {
        var ws = Workspace.of(Map.of("a/Calc.java", CALC, "b/User.java", USER));

        var result = ws.invoke("change-signature", Map.of("method", "a.Calc#add(int,int,int)",
                "parameters", "int b, int a, long scale = 1"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Calc.java")).contains("public int add(int b, int a, long scale) {", "return add(2, 1, 1);");
    }

    /** With no calls, a new parameter needs no value for them. */
    @Test
    void aMethodNobodyCallsTakesANewParameterWithNoValue() {
        var ws = Workspace.of(Map.of("a/Calc.java", CALC.replace("return add(1, 2, 0);", "return 0;")));

        var result = ws.invoke("change-signature", Map.of("method", "a.Calc#add(int,int,int)", "parameters", "int a, int b, String label"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Calc.java")).contains("public int add(int a, int b, String label) {");
    }

    /**
     * A parameter that goes takes its argument from every call: the locals the argument read are read no more there,
     * on purpose, and stay where they are still read.
     */
    @Test
    void removingAParameterDropsTheLocalsItsCallsPassedAndKeepsThem() {
        var ws = Workspace.of(Map.of("a/Shop.java", """
                package a;

                class Shop {
                    void save(String id, String name, Object customer) {
                        System.out.println(customer);
                    }

                    void run(String line) {
                        String id = line.trim();
                        String name = line.strip();
                        Object customer = id + name;
                        save(id, name, customer);
                    }
                }
                """));

        var result = ws.invoke("change-signature", Map.of("method", "a.Shop#save(String,String,Object)", "parameters", "Object customer"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Shop.java")).contains("    void save(Object customer) {", "        save(customer);",
                "        Object customer = id + name;");
        assertThat(ws.errors()).isEmpty();
    }

    /** A default value is what each call passes, as Java: the commas of a call in it, and a comparison, are its own. */
    @Test
    void aDefaultValueMayHaveCommasAndComparisons() {
        var ws = Workspace.of(Map.of("a/Calc.java", CALC, "b/User.java", USER));

        var result = ws.invoke("change-signature", Map.of("method", "a.Calc#add(int,int,int)",
                "parameters", "int a, int b, int unused, int top = Math.max(4, 5), boolean small = 4 < 5"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Calc.java")).contains("public int add(int a, int b, int unused, int top, boolean small) {",
                "return add(1, 2, 0, Math.max(4, 5), 4 < 5);");
    }

    /** An argument that does something would no longer do it: removing its parameter is refused. */
    @Test
    void removingAParameterWhoseCallsPassWhatDoesSomethingAborts() {
        var shop = """
                package a;

                class Shop {
                    void save(Object customer, int count) {
                        System.out.println(customer);
                    }

                    int next() {
                        return 1;
                    }

                    void run() {
                        save("c", next());
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Shop.java", shop));

        var result = ws.invoke("change-signature", Map.of("method", "a.Shop#save(Object,int)", "parameters", "Object customer"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("delete.effects");
        assertThat(ws.source("a/Shop.java")).isEqualTo(shop);
    }

    @Test
    void removingAParameterTheBodyUsesAborts() {
        var ws = Workspace.of(Map.of("a/Calc.java", CALC, "b/User.java", USER));

        var result = ws.invoke("change-signature", Map.of("method", "a.Calc#add(int,int,int)", "parameters", "a, unused"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("delete.referenced");
        assertThat(ws.source("a/Calc.java")).isEqualTo(CALC);
    }
}
