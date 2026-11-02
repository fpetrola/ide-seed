package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A static field becomes one each object has. Its value was shared; with more than one object it no longer is, which
 * no verifier can tell: it says it changes meaning.
 */
class MakeFieldInstanceTest {

    private static final String REPO = """
            package a;

            public class Repo {
                private static int count;

                void add() {
                    count++;
                }

                int count() {
                    return count;
                }
            }
            """;

    @Test
    void aStaticFieldUsedFromObjectsBecomesTheirs() {
        var ws = Workspace.of(Map.of("a/Repo.java", REPO));

        var result = ws.invoke("make-field-instance", Map.of("field", "a.Repo#count"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("    private int count;").doesNotContain("static");
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.capabilities().get("make-field-instance").description()).startsWith("Changes meaning:");
    }

    /** A static method that uses it, called with no object, cannot have one: refused. */
    @Test
    void aFieldAStaticMethodCalledWithNoObjectUsesIsRefused() {
        var ws = Workspace.of(Map.of("a/Repo.java", REPO.replace("int count() {", "static int count() {"), "a/Main.java", """
                package a;

                public class Main {
                    public static void main(String[] args) {
                        System.out.println(Repo.count());
                    }
                }
                """));

        var result = ws.invoke("make-field-instance", Map.of("field", "a.Repo#count"));

        assertThat(result.violations()).extracting(Violation::code).isNotEmpty();
        assertThat(ws.source("a/Repo.java")).contains("private static int count;");
    }

    @Test
    void aFieldThatIsNotStaticIsRefused() {
        var ws = Workspace.of(Map.of("a/Repo.java", REPO.replace("private static int count;", "private int count;")));

        var result = ws.invoke("make-field-instance", Map.of("field", "a.Repo#count"));

        assertThat(result.violations()).isNotEmpty();
    }

    /** Named through its type where an object uses it, it is named as the object's own. */
    @Test
    void aUseThroughItsTypeBecomesTheObjects() {
        var ws = Workspace.of(Map.of("a/Repo.java", REPO.replace("        count++;", "        Repo.count++;")
                .replace("return count;", "int count = 1;\n        return count + Repo.count;")));

        var result = ws.invoke("make-field-instance", Map.of("field", "a.Repo#count"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("        count++;", "return count + this.count;").doesNotContain("Repo.count");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void aFieldDeclaredAfterItsUsesToo() {
        var ws = Workspace.of(Map.of("a/Repo.java", """
                package a;

                public class Repo {
                    void add() {
                        Repo.count += 2;
                    }

                    private static int count;
                }
                """));

        var result = ws.invoke("make-field-instance", Map.of("field", "a.Repo#count"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("        count += 2;", "    private int count;");
        assertThat(ws.errors()).isEmpty();
    }
}
