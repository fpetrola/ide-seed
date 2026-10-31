package ide.refactoring;

import ide.refactoring.script.Script;
import ide.refactoring.script.ScriptParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogBudgetTest {

    @Test
    void everyCatalogScriptHasAtMostTwentyStatements() throws Exception {
        try (var in = getClass().getResourceAsStream("/ide/refactoring/standard.ref")) {
            assertThat(in).as("the catalog is on the classpath").isNotNull();
            var scripts = ScriptParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(scripts).isNotEmpty();
            assertThat(scripts).allSatisfy(s -> assertThat(((Script) s).size()).as(s.id()).isLessThanOrEqualTo(20));
        }
    }
}
