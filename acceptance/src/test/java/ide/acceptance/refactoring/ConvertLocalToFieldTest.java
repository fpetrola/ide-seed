package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A local becomes a field of its type, as IntelliJ's Convert Local to Field: the field is declared with no value (static
 * if the method is), the declaration gives it its value where it was given, and the local's uses use it. What runs,
 * runs where it ran.
 */
class ConvertLocalToFieldTest {

    @Test
    void theDeclarationGivesTheFieldItsValueWhereItWasGiven() {
        var ws = Workspace.of(Map.of("a/Loader.java", """
                package a;

                class Loader {
                    public static void main(String[] args) {
                        System.out.println("first");
                        StringBuilder text = new StringBuilder("a");
                        text.append("b");
                        System.out.println(text);
                    }
                }
                """));

        var result = ws.invoke("convert-local-to-field", Map.of("local", "a.Loader#main(String[])/text"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Loader.java")).isEqualTo("""
                package a;

                class Loader {
                    private static StringBuilder text;

                    public static void main(String[] args) {
                        System.out.println("first");
                        text = new StringBuilder("a");
                        text.append("b");
                        System.out.println(text);
                    }
                }
                """);
        assertThat(ws.errors()).isEmpty();
    }

    /** A field of that name is there already: the local would go into another's value. */
    @Test
    void aNameTheTypeHasIsRefused() {
        var source = """
                package a;

                class Counter {
                    int count;

                    int next() {
                        int count = 1;
                        return count;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Counter.java", source));

        var result = ws.invoke("convert-local-to-field", Map.of("local", "a.Counter#next()/count"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("projection.duplicate");
        assertThat(ws.source("a/Counter.java")).isEqualTo(source);
    }
}
