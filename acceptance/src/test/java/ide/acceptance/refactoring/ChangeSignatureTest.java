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

    @Test
    void removingAParameterTheBodyUsesAborts() {
        var ws = Workspace.of(Map.of("a/Calc.java", CALC, "b/User.java", USER));

        var result = ws.invoke("change-signature", Map.of("method", "a.Calc#add(int,int,int)", "parameters", "a, unused"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("delete.referenced");
        assertThat(ws.source("a/Calc.java")).isEqualTo(CALC);
    }
}
