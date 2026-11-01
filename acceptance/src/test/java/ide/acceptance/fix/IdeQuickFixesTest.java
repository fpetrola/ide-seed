package ide.acceptance.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The quick fixes IDEs have for Eclipse's usual errors and warnings, each deduced from the knowledge. */
class IdeQuickFixesTest {

    @Test
    void aClassMissingWhatItsSupertypesLeaveAbstractImplementsItOrBecomesAbstract() {
        var sources = Map.of("a/Shape.java", """
                package a;

                abstract class Shape {
                    abstract int side();
                }
                """, "a/Named.java", """
                package a;

                interface Named {
                    String name(java.util.List<String> parts);
                }
                """, "a/Square.java", """
                package a;

                class Square extends Shape implements Named {
                }
                """);
        var ws = Workspace.of(sources);

        assertThat(ws.fixes("a/Square.java", 3)).contains("implement methods of Square", "make Square abstract");

        ws.fix("a/Square.java", 3, "implement methods of Square");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Square.java")).contains("""
                    int side() {
                        return 0;
                    }
                """, """
                    public String name(java.util.List<String> parts) {
                        return null;
                    }
                """);

        var other = Workspace.of(sources);
        other.fix("a/Square.java", 3, "make Square abstract");
        assertThat(other.errors()).isEmpty();
        assertThat(other.source("a/Square.java")).contains("abstract class Square extends Shape implements Named {");
    }

    @Test
    void aMethodCalledFromAStaticPlaceBecomesStatic() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    static int twice() {
                        return base() * 2;
                    }

                    int base() {
                        return 21;
                    }
                }
                """));

        ws.fix("a/U.java", 5, "make base() static");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/U.java")).contains("static int base() {");
    }

    @Test
    void anUnusedPrivateMemberIsRemoved() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    private int count;

                    private void unused() {
                    }
                }
                """));

        assertThat(ws.diagnostics()).extracting(d -> d.code() + " " + d.severity())
                .containsExactlyInAnyOrder("ecj.UnusedPrivateField WARNING", "ecj.UnusedPrivateMethod WARNING");

        ws.fix("a/U.java", 4, "remove count");
        ws.fix("a/U.java", 4, "remove unused");

        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void aMemberTheCallerCannotSeeIsOpenedToIt() {
        var ws = Workspace.of(Map.of("a/A.java", """
                package a;

                class A {
                    private int secret() {
                        return 1;
                    }
                }
                """, "a/B.java", """
                package a;

                class B {
                    int peek(A a) {
                        return a.secret();
                    }
                }
                """));

        assertThat(ws.fixes("a/B.java", 5)).contains("open-visibility a.A#secret() private -> package");

        ws.fix("a/B.java", 5, "open-visibility a.A#secret() private -> package");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/A.java")).contains("    int secret() {");
    }

    @Test
    void aValueOfAnotherTypeChangesTheTypeOfItsPlace() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    long label = "none";

                    long title() {
                        return "untitled";
                    }
                }
                """));

        assertThat(ws.fixes("a/U.java", 4)).contains("change type of label to String");
        assertThat(ws.fixes("a/U.java", 7)).contains("change return type of title() to String");

        ws.fix("a/U.java", 4, "change type of label to String");
        ws.fix("a/U.java", 7, "change return type of title() to String");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/U.java")).contains("String label = \"none\";", "String title() {");
    }

    @Test
    void aNameNothingDeclaresBecomesAParameterOfItsMethodAndItsCallsPassWhatItsTypeStartsWith() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    long total() {
                        return limit * 2;
                    }

                    long twice() {
                        return total();
                    }
                }
                """));

        assertThat(ws.fixes("a/U.java", 5)).contains("create field limit in U", "create parameter limit in total()");

        ws.fix("a/U.java", 5, "create parameter limit in total()");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/U.java")).contains("long total(int limit) {", "return total(0);");
    }
}
