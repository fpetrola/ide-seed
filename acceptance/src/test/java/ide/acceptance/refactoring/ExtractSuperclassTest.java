package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractSuperclassTest {

    @Test
    void theNewSuperclassTakesOverASuperclassFromOutsideTheWorkspace() {
        var ws = Workspace.of(Map.of("a/B.java", """
                package a;

                import java.util.ArrayList;

                public class B extends ArrayList<String> {
                    protected int x;
                }
                """));

        var result = ws.invoke("extract-superclass", Map.of("type", "a.B", "name", "Base", "fields", List.of("a.B#x")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Base.java")).contains("import java.util.ArrayList;", "public class Base extends ArrayList<String> {");
        assertThat(ws.source("a/B.java")).contains("public class B extends Base {");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void extractsSuperclassWithSelectedFieldsComposingPullUps() {
        var ws = Workspace.of(Map.of(
                "a/B.java", """
                        package a;

                        public class B {

                            private int x;
                            protected String name;
                            int count;

                            int get() {
                                return x;
                            }
                        }
                        """));

        var result = ws.invoke("extract-superclass", Map.of(
                "type", "a.B",
                "name", "Base",
                "fields", List.of("a.B#x", "a.B#name")));

        assertThat(result.violations()).isEmpty();
        assertThat(result.repairs()).isEmpty();
        assertThat(ws.source("a/Base.java")).isEqualTo("""
                package a;

                public class Base {

                    int x;

                    protected String name;
                }
                """);
        assertThat(ws.source("a/B.java")).isEqualTo("""
                package a;

                public class B extends Base {

                    int count;

                    int get() {
                        return x;
                    }
                }
                """);
    }

    @Test
    void newSuperclassTakesOverTheOldOne() {
        var ws = Workspace.of(Map.of(
                "a/A.java", """
                        package a;

                        public class A {
                        }
                        """,
                "a/B.java", """
                        package a;

                        public class B extends A {

                            protected int x;
                        }
                        """));

        var result = ws.invoke("extract-superclass", Map.of(
                "type", "a.B", "name", "Mid", "fields", List.of("a.B#x")));

        assertThat(result.applied()).isTrue();
        assertThat(ws.source("a/Mid.java")).isEqualTo("""
                package a;

                public class Mid extends A {

                    protected int x;
                }
                """);
        assertThat(ws.source("a/B.java")).isEqualTo("""
                package a;

                public class B extends Mid {
                }
                """);
    }

    /**
     * A blank final its class's constructor gives a value, pulled up: the superclass's constructor takes the value and
     * gives it, and the constructor passes it to it first instead, as IntelliJ's Extract Superclass leaves it.
     */
    @Test
    void aBlankFinalPulledUpIsGivenByTheSuperclassConstructorThatTheConstructorCalls() {
        var ws = Workspace.of(Map.of("a/Unset.java", """
                package a;

                public class Unset {
                    private final int limit;

                    int twice() {
                        return limit * 2;
                    }

                    Unset(int limit) {
                        this.limit = limit;
                    }
                }
                """, "a/User.java", """
                package a;

                class User {
                    Unset unset = new Unset(5);
                }
                """));

        var result = ws.invoke("extract-superclass", Map.of("type", "a.Unset", "name", "AbstractUnset", "fields", List.of("a.Unset#limit")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/AbstractUnset.java")).contains("final int limit;", """
                    AbstractUnset(int limit) {
                        this.limit = limit;
                    }
                """);
        assertThat(ws.source("a/Unset.java")).contains("""
                    Unset(int limit) {
                        super(limit);
                    }
                """);
    }
}
