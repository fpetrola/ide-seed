package ide.refactor;

import ide.refactor.script.Script;
import ide.refactor.script.ScriptParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogBudgetTest {

    @Test
    void everyCatalogScriptHasAtMostTwentyStatements() throws Exception {
        try (var in = getClass().getResourceAsStream("/ide/refactor/standard.ref")) {
            var scripts = ScriptParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(scripts).isNotEmpty();
            assertThat(scripts).allSatisfy(s -> assertThat(((Script) s).size()).as(s.id()).isLessThanOrEqualTo(20));
        }
    }

    @Test
    void atMostOneCompositeIsWrittenInJava() {
        var ws = ide.workbench.Workspace.of(java.util.Map.of());
        assertThat(ws.library()).filteredOn(entry -> entry.startsWith("composite ")).hasSizeLessThanOrEqualTo(1);
    }
}
