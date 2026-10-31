package ide.complete;

import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionTest {

    private static final String P = """
            package a;

            public class P extends Base {
                private int secret;
                public int x;
                int pkg;

                public int getX() {
                    return x;
                }

                void helper() {
                }

                protected void prot() {
                }
            }
            """;
    private static final String BASE = """
            package a;

            public class Base {
                public String name;
            }
            """;

    @Test
    void completesAccessibleMembersOfTheReceiverIncludingInherited() {
        var u = """
                package b;

                import a.P;

                class U {
                    int use(P p) {
                        return 0;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/P.java", P, "a/Base.java", BASE, "b/U.java", u));
        var editing = u.replace("return 0;", "return p.");

        assertThat(ws.complete("b/U.java", editing, editing.indexOf("p.") + 2))
                .containsExactly("x", "getX()", "name");
        assertThat(ws.complete("b/U.java", editing.replace("p.", "p.ge"), editing.indexOf("p.") + 4))
                .containsExactly("getX()");
    }

    @Test
    void samePackageSeesPackagePrivateAndProtectedButNotPrivate() {
        var q = """
                package a;

                class Q {
                    void use(P p) {
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/P.java", P, "a/Base.java", BASE, "a/Q.java", q));
        var editing = q.replace("void use(P p) {\n", "void use(P p) {\n        p.");

        assertThat(ws.complete("a/Q.java", editing, editing.indexOf("p.") + 2))
                .containsExactly("x", "pkg", "getX()", "helper()", "prot()", "name");
    }

    @Test
    void aBareNameCompletesLocalsThenMembersOfTheEnclosingTypes() {
        var r = """
                package a;

                class R extends Base {
                    int count;

                    int compute(int cost) {
                        int carry = 1;
                        return 0;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Base.java", BASE, "a/R.java", r));
        var editing = r.replace("return 0;", "return c");

        assertThat(ws.complete("a/R.java", editing, editing.indexOf("return c") + 8))
                .containsExactly("carry", "cost", "count", "compute()");
        var all = ws.complete("a/R.java", editing.replace("return c", "return "), editing.indexOf("return c") + 7);
        assertThat(all).contains("carry", "cost", "count", "compute()", "name");
    }

    @Test
    void aTypeAsReceiverCompletesItsStaticMembers() {
        var money = """
                package a;

                public class Money {
                    public static final Money ZERO = null;
                    public long cents;

                    public static Money of(long cents) {
                        return null;
                    }

                    public long cents() {
                        return cents;
                    }
                }
                """;
        var u = """
                package a;

                class U {
                    Object m() {
                        return null;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Money.java", money, "a/U.java", u));
        var editing = u.replace("return null;", "return Money.");

        assertThat(ws.complete("a/U.java", editing, editing.indexOf("Money.") + 6)).containsExactly("ZERO", "of()");
    }
}
