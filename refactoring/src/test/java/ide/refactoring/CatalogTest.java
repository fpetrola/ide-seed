package ide.refactoring;

import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Program;
import ide.projection.kit.MemoryProjection;
import ide.refactoring.script.Script;
import ide.refactoring.script.ScriptParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The catalog's own rules: small scripts, and no code written in any language. */
class CatalogTest {

    private static String catalog() throws IOException {
        try (var in = CatalogTest.class.getResourceAsStream("/ide/refactoring/standard.ref")) {
            assertThat(in).as("the catalog is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** What {@code site} uses, as {@code "<address> <mode>"}. */
    private static List<String> uses(Program program, ElementId site) {
        return program.referencesFrom(site).stream()
                .map(r -> program.element(r.target()).qualified() + " " + r.mode().label()).toList();
    }

    @Test
    void everyCatalogScriptHasAtMostTwentyStatements() throws IOException {
        var scripts = ScriptParser.parse(catalog());

        assertThat(scripts).isNotEmpty();
        assertThat(scripts).allSatisfy(s -> assertThat(((Script) s).size()).as(s.id()).isLessThanOrEqualTo(20));
    }

    /** Its bodies are said in no language: they run on a projection that has none, and do what they say. */
    @Test
    void itsBodiesRunOnAProjectionWithNoLanguage() throws IOException {
        var projection = MemoryProjection.builder()
                .type("a.A").field("a.A", "x", "int").field("a.A", "n", "int").fact("a.A#n", Fact.STATIC, "true")
                .build();
        var run = Run.on(projection, Library.standard());

        var getter = run.call("generate-getter", Map.of("field", "a.A#x"));
        var setter = run.call("generate-setter", Map.of("field", "a.A#x"));
        var staticSetter = run.call("generate-setter", Map.of("field", "a.A#n"));

        var p = projection.program();
        assertThat(uses(p, getter)).containsExactly("a.A#x read");
        assertThat(uses(p, setter)).containsExactly("a.A#x write", "a.A#setX(int)/x read");
        assertThat(uses(p, staticSetter)).containsExactly("a.A#n write", "a.A#setN(int)/n read");
        assertThat(catalog()).doesNotContain("leak");
    }
}
