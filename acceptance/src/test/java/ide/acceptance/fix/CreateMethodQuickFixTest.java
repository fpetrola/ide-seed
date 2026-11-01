package ide.acceptance.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A call to no method: the fix creates it where the call looks for it, with what the call passes and expects. */
class CreateMethodQuickFixTest {

    @Test
    void itTakesWhatTheCallPassesAndAnswersWhatItsPlaceExpects() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    long total(java.util.List<String> names) {
                        return measure(names, 3);
                    }
                }
                """));

        assertThat(ws.fixes("a/U.java", 5)).contains("create method measure in U");
        assertThat(ws.asks("a/U.java", 5, "create method measure in U")).containsExactly("type: return type of the new method [long]");

        ws.fix("a/U.java", 5, "create method measure in U");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/U.java")).contains("""
                    long measure(java.util.List<String> names, int i) {
                        return 0;
                    }
                """);
    }

    @Test
    void fromAStaticMethodItIsStaticAndACallWhoseValueNothingTakesAnswersNothing() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    static void main() {
                        log("started");
                    }
                }
                """));

        // that the caller is static is a condition of the method made for it, never something to change
        assertThat(ws.fixes("a/U.java", 5)).containsExactly("create method log in U");

        ws.fix("a/U.java", 5, "create method log in U");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/U.java")).contains("static void log(String string) {", "    static void main() {");
    }

    @Test
    void aCallOnAnotherObjectCreatesItInThatObjectsClass() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    static void main(V v) {
                        v.reset();
                    }
                }
                """, "a/V.java", """
                package a;

                class V {
                }
                """));

        ws.fix("a/U.java", 5, "create method reset in V");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/V.java")).contains("    void reset() {").doesNotContain("static");
    }
}
