package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EncapsulateFieldTest {

    private static final String P = """
            package a;

            public class P {
                public int x;

                int twice() {
                    return x * 2;
                }
            }
            """;

    @Test
    void generatesAccessorsAndRetargetsOutsideReadsAndWrites() {
        var ws = Workspace.of(Map.of("a/P.java", P, "b/U.java", """
                package b;

                import a.P;

                class U {
                    int use(P p) {
                        p.x = 3;
                        return p.x + 1;
                    }
                }
                """));

        var result = ws.invoke("encapsulate-field", Map.of("field", "a.P#x"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/P.java")).isEqualTo("""
                package a;

                public class P {
                    private int x;

                    int twice() {
                        return x * 2;
                    }

                    public int getX() {
                        return x;
                    }

                    public void setX(int x) {
                        this.x = x;
                    }
                }
                """);
        assertThat(ws.source("b/U.java")).isEqualTo("""
                package b;

                import a.P;

                class U {
                    int use(P p) {
                        p.setX(3);
                        return p.getX() + 1;
                    }
                }
                """);
    }

    @Test
    void compoundAssignmentCannotBeRetargetedAndAborts() {
        var u = """
                package b;

                import a.P;

                class U {
                    void bump(P p) {
                        p.x += 2;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/P.java", P, "b/U.java", u));

        var result = ws.invoke("encapsulate-field", Map.of("field", "a.P#x"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("retarget.update");
        assertThat(ws.source("a/P.java")).isEqualTo(P);
        assertThat(ws.source("b/U.java")).isEqualTo(u);
    }
}
