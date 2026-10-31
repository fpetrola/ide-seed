package ide.workbench;

import ide.core.Param;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilitiesTest {

    private static final String P = """
            package a;

            public class P {
                public int x;
            }
            """;

    @Test
    void everyRefactoringAndSessionOperationIsACapabilityWithASchema() {
        var registry = Workspace.of(Map.of("a/P.java", P)).capabilities();

        assertThat(registry.ids()).contains("rename", "encapsulate-field", "change-signature", "move",
                "ls", "show", "diff", "undo", "fixes", "fix", "why", "complete", "preview", "expand", "define",
                "library", "diagnostics");
        assertThat(registry.get("rename").params()).extracting(Param::name, Param::type)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("element", "element"),
                        org.assertj.core.groups.Tuple.tuple("name", "text"));
        assertThat(registry.help("encapsulate-field"))
                .isEqualTo("encapsulate-field field:element(FIELD) — Accessors for a field; uses outside its type go through them; the field becomes private");
    }

    @Test
    void capabilitiesAreInvokedWithAMapAndAnswerJson() {
        var ws = Workspace.of(Map.of("a/P.java", P));
        var registry = ws.capabilities();

        assertThat(registry.invoke("ls", Map.of())).isEqualTo("[\"a/P.java\"]");
        assertThat(registry.invoke("rename", Map.of("element", "a.P#x", "name", "y")))
                .isEqualTo("{\"applied\":true,\"violations\":[],\"repairs\":[],\"steps\":[\"' rename a.P#x -> y\"]}");
        assertThat(registry.invoke("show", Map.of("path", "a/P.java"))).contains("public int y;");
        assertThat(registry.invoke("undo", Map.of())).isEqualTo("\"ok\"");
        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }

    @Test
    void definedRefactoringsBecomeCapabilities() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        ws.capabilities().invoke("define", Map.of("text", """
                refactoring make-private "Makes a member private"
                  param member : element(FIELD,METHOD) "the member"
                  set ${member} visibility private
                end
                """));

        assertThat(ws.capabilities().help("make-private")).isEqualTo("make-private member:element(FIELD,METHOD) — Makes a member private");
    }
}
