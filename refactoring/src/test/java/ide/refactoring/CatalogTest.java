package ide.refactoring;

import ide.refactoring.script.Script;
import ide.refactoring.script.ScriptParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalog's own rules: small scripts, and its known leaks marked where they are. */
class CatalogTest {

    private static String catalog() throws IOException {
        try (var in = CatalogTest.class.getResourceAsStream("/ide/refactoring/standard.ref")) {
            assertThat(in).as("the catalog is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyCatalogScriptHasAtMostTwentyStatements() throws IOException {
        var scripts = ScriptParser.parse(catalog());

        assertThat(scripts).isNotEmpty();
        assertThat(scripts).allSatisfy(s -> assertThat(((Script) s).size()).as(s.id()).isLessThanOrEqualTo(20));
    }

    /** Bodies are Java until there is a neutral body language: each one says so, so none passes unnoticed. */
    @Test
    void everyJavaBodyIsMarkedAsAKnownLeak() throws IOException {
        var lines = catalog().lines().map(String::strip).toList();

        for (var i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("set \\$\\{[^}]+} body .*")) {
                assertThat(lines.get(i - 1)).as(lines.get(i)).isEqualTo("# leak: Java body");
            }
        }
        assertThat(lines).filteredOn("# leak: Java body"::equals).hasSize(4);
    }
}
