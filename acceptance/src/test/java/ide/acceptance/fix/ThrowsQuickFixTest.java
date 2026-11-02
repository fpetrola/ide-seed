package ide.acceptance.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A checked exception neither caught nor declared, from what Java says (java.rules): the callable declares it. Declaring
 * it changes nothing of what runs; what calls the callable the compiler judges.
 */
class ThrowsQuickFixTest {

    @Test
    void aCheckedExceptionNeitherCaughtNorDeclaredIsDeclaredByItsMethod() {
        var ws = Workspace.of(Map.of("a/Config.java", """
                package a;

                import java.nio.file.Files;
                import java.nio.file.Path;

                class Config {
                    String read(Path path) throws IllegalStateException {
                        return Files.readString(path);
                    }
                }
                """));

        assertThat(ws.fixes("a/Config.java", 8)).containsExactly("add throws java.io.IOException to read()");

        ws.fix("a/Config.java", 8, "add throws java.io.IOException to read()");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Config.java")).contains("import java.io.IOException;",
                "    String read(Path path) throws IllegalStateException, IOException {");
    }

    /** What a callable declares it throws, set by hand: a type it no longer names is gone on purpose. */
    @Test
    void aTypeSubsumedByAnotherCanBeTakenOutOfWhatAMethodDeclares() {
        var ws = Workspace.of(Map.of("a/Config.java", """
                package a;

                import java.io.IOException;
                import java.nio.file.Files;
                import java.nio.file.Path;

                class Config {
                    String read(Path path) throws IOException, Exception {
                        return Files.readString(path);
                    }
                }
                """));

        var result = ws.invoke("set", Map.of("element", "a.Config#read(Path)", "attribute", "throws", "value", "Exception"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Config.java")).contains("    String read(Path path) throws Exception {");
        assertThat(ws.errors()).isEmpty();
    }

    /** One more that is a supertype of what it declares: those it covers go, as they are said by it. */
    @Test
    void aSupertypeDeclaredCoversTheSubtypesAlreadyThere() {
        var ws = Workspace.of(Map.of("a/Config.java", """
                package a;

                import java.io.IOException;
                import java.sql.SQLException;

                class Config {
                    void run() throws IOException, SQLException {
                        throw new Exception("any");
                    }
                }
                """));

        ws.fix("a/Config.java", 8, "add throws Exception to run()");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/Config.java")).contains("    void run() throws Exception {");
    }
}
