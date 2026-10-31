package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractSuperclassTest {

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
        assertThat(result.repairs()).containsExactly("open-visibility a.Base#x private -> package");
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
}
