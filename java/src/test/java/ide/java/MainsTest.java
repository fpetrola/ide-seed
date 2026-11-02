package ide.java;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The classes a program can be run from: a top-level class, first of its file, with a main method Java launches. */
class MainsTest {

    @Test
    void aClassWithAMainIsWhereAProgramRunsFrom() {
        var projection = JavaProjection.of(Map.of(
                "a/App.java", """
                        package a;

                        public class App {
                            public static void main(String[] args) {
                                System.out.println(new Helper().greet());
                            }
                        }
                        """,
                "a/Helper.java", """
                        package a;

                        class Helper {
                            String greet() {
                                return "hi";
                            }

                            static void main(int notTheOne) {
                            }
                        }
                        """,
                "a/Script.java", """
                        package a;

                        class Script {
                            void main() {
                                System.out.println("instance main, as Java 25 runs it");
                            }
                        }
                        """,
                "a/Varargs.java", """
                        package a;

                        public class Varargs {
                            public static void main(String... args) {
                            }

                            static class Inner {
                                public static void main(String[] args) {
                                }
                            }
                        }
                        """,
                "b/Second.java", """
                        package b;

                        class First {
                        }

                        public class Second {
                            public static void main(String[] args) {
                            }
                        }
                        """));

        assertThat(projection.mains()).extracting(m -> m.type() + " " + m.path() + ":" + m.line() + ":" + m.methodLine())
                .containsExactly("a.App a/App.java:3:4", "a.Script a/Script.java:3:4", "a.Varargs a/Varargs.java:3:4");
    }
}
