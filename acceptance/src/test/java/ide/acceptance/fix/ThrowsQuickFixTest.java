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
}
