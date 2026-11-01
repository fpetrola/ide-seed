package ide.acceptance.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinalFieldQuickFixTest {

    private static final String P = """
            package a;

            class P {
                private final int limit;
                private final String label;

                int twice() {
                    return limit * 2;
                }
            }
            """;

    @Test
    void uninitializedFinalOffersAnInitializerOrDroppingFinal() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        assertThat(ws.fixes("a/P.java", 4)).containsExactly("initialize limit in its declaration", "make limit not final",
                "create constructor P(), then initialize limit in the constructors");
        assertThat(ws.asks("a/P.java", 4, "initialize limit in its declaration")).containsExactly("value: initial value [0]");
        assertThat(ws.asks("a/P.java", 5, "initialize label in its declaration")).containsExactly("value: initial value [null]");

        ws.fix("a/P.java", 4, "initialize limit in its declaration", Map.of("value", "10"));
        ws.fix("a/P.java", 5, "make label not final");

        assertThat(ws.source("a/P.java")).contains("private final int limit = 10;", "private String label;");
        assertThat(ws.errors()).isEmpty();
    }

    /**
     * Java gives a blank final field its value where it is declared or in every constructor of its type; a type that
     * declares none has the one the language gives, which assigns nothing. So a constructor is declared first, and then
     * each constructor takes the value, the calls passing what the field's type starts with.
     */
    @Test
    void aBlankFinalIsInitializedInTheConstructorsDeclaringOneIfTheTypeHasNone() {
        var ws = Workspace.of(Map.of("a/Q.java", """
                package a;

                class Q {
                    private final int limit;

                    int twice() {
                        return limit * 2;
                    }
                }
                """, "a/User.java", """
                package a;

                class User {
                    Q q = new Q();
                }
                """));

        assertThat(ws.fixes("a/Q.java", 4)).contains("create constructor Q(), then initialize limit in the constructors");

        ws.fix("a/Q.java", 4, "create constructor Q(), then initialize limit in the constructors");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Q.java")).contains("""
                    Q(int limit) {
                        this.limit = limit;
                    }
                """);
        assertThat(ws.source("a/User.java")).contains("Q q = new Q(0);");
    }

    @Test
    void aBlankFinalIsInitializedInTheConstructorsThereAreKeepingWhatTheyDo() {
        var ws = Workspace.of(Map.of("a/R.java", """
                package a;

                class R {
                    private final int limit;
                    private final String label;

                    R(String label) {
                        // the label, as given
                        this.label = label;
                    }
                }
                """));
        var line = 7; // Eclipse says it at the constructor that does not give limit a value

        assertThat(ws.fixes("a/R.java", line)).contains("initialize limit in the constructors")
                .doesNotContain("create constructor R(), then initialize limit in the constructors");

        ws.fix("a/R.java", line, "initialize limit in the constructors");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/R.java")).contains("""
                    R(String label, int limit) {
                        // the label, as given
                        this.label = label;
                        this.limit = limit;
                    }
                """);
    }

    /**
     * A constructor may not give a final of its superclass its value: it is passed to a constructor of the superclass
     * instead, which takes it and stores it, the call first; the superclass declares one if it has none. Deduced from
     * who may assign a final and how a constructor gives a field its value.
     */
    @Test
    void aFinalOfTheSuperclassIsPassedToItsConstructor() {
        var ws = Workspace.of(Map.of("a/Base.java", """
                package a;

                class Base {
                    final int limit;
                }
                """, "a/Sub.java", """
                package a;

                class Sub extends Base {
                    Sub(int limit) {
                        this.limit = limit;
                    }
                }
                """));

        assertThat(ws.fixes("a/Sub.java", 5)).containsExactly("make limit not final",
                "create constructor Base(), then pass limit to the constructor of Base");

        ws.fix("a/Sub.java", 5, "create constructor Base(), then pass limit to the constructor of Base");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Base.java")).contains("""
                    Base(int limit) {
                        this.limit = limit;
                    }
                """);
        assertThat(ws.source("a/Sub.java")).contains("""
                    Sub(int limit) {
                        super(limit);
                    }
                """);
    }
}
