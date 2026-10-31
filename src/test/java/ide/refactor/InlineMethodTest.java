package ide.refactor;

import ide.coherence.Violation;
import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InlineMethodTest {

    @Test
    void inlinesEveryCallSubstitutingArgumentsWithMinimalParenthesesAndDeletesTheMethod() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int base;

                    int scaled(int k) {
                        return base * k;
                    }

                    int use(int v) {
                        return scaled(v + 1) + scaled(2) * 3;
                    }
                }
                """));

        var result = ws.invoke("inline-method", Map.of("method", "a.P#scaled(int)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).isEqualTo("""
                package a;

                class P {
                    int base;

                    int use(int v) {
                        return base * (v + 1) + base * 2 * 3;
                    }
                }
                """);
        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void anArgumentThatWouldBeEvaluatedTwiceIsRefused() {
        var p = """
                package a;

                class P {
                    int square(int k) {
                        return k * k;
                    }

                    int use(int v) {
                        return square(next(v));
                    }

                    int next(int v) {
                        return v + 1;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/P.java", p));

        var result = ws.invoke("inline-method", Map.of("method", "a.P#square(int)"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("inline.evaluation-count");
        assertThat(ws.source("a/P.java")).isEqualTo(p);
    }
}
