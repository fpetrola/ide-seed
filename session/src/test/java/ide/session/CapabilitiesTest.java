package ide.session;

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
    void aPlaceInABodyIsInsideItsMethodForWhatTheAdaptersAsk() {
        var ws = Workspace.of(Map.of("a/A.java", """
                package a;

                class A {
                    int twice(int n) {
                        int sum = n + n;
                        return sum * 2;
                    }
                }
                """));

        assertThat(ws.capabilities().invoke("inside", Map.of("path", "a/A.java", "line", "5", "column", "23")))
                .isEqualTo("{\"element\":\"a.A#twice(int)\",\"kind\":\"METHOD\"}");
    }

    @Test
    void capabilitiesAreInvokedWithAMapAndAnswerJson() {
        var ws = Workspace.of(Map.of("a/P.java", P));
        var registry = ws.capabilities();

        assertThat(registry.invoke("ls", Map.of())).isEqualTo("[\"a/P.java\"]");
        assertThat(registry.invoke("rename", Map.of("element", "a.P#x", "name", "y")))
                .isEqualTo("{\"applied\":true,\"violations\":[],\"repairs\":[],\"steps\":[\"' rename a.P#x -> y\"],"
                        + "\"guarantee\":\"guaranteed\",\"obligations\":[]}");
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

    /** What an editor asks: what the caret is on, where something is, the elements to choose from, and a fix's questions. */
    @Test
    void anEditorAsksWhereThingsAreAndAnswersWhatAFixAsks() {
        var ws = Workspace.of(Map.of("a/P.java", P, "a/U.java", """
                package a;

                class U {
                    int total() {
                        return count;
                    }
                }
                """));
        var capabilities = ws.capabilities();

        assertThat(capabilities.invoke("at", Map.of("path", "a/P.java", "line", "4", "column", "16")))
                .isEqualTo("{\"element\":\"a.P#x\",\"kind\":\"FIELD\"}");
        assertThat(capabilities.invoke("at", Map.of("path", "a/P.java", "line", "1", "column", "1"))).isEqualTo("null");
        assertThat(capabilities.invoke("where", Map.of("element", "a.P#x"))).isEqualTo("{\"path\":\"a/P.java\",\"line\":4,\"column\":16}");
        assertThat(capabilities.invoke("elements", Map.of("kinds", "CLASS,FIELD"))).isEqualTo(
                "[{\"element\":\"a.P\",\"kind\":\"CLASS\"},{\"element\":\"a.P#x\",\"kind\":\"FIELD\"},{\"element\":\"a.U\",\"kind\":\"CLASS\"}]");
        assertThat(capabilities.invoke("elements", Map.of("path", "a/U.java"))).contains("{\"element\":\"a.U#total()\",\"kind\":\"METHOD\"}");
        assertThat(capabilities.invoke("asks", Map.of("path", "a/U.java", "line", "5", "title", "create field count in U")))
                .isEqualTo("[{\"name\":\"type\",\"question\":\"type of the new field\",\"default\":\"int\"}]");

        capabilities.invoke("fix", Map.of("path", "a/U.java", "line", "5", "title", "create field count in U", "answers", Map.of("type", "short")));
        assertThat(ws.source("a/U.java")).contains("short count;");

        assertThat(capabilities.invoke("edit", Map.of("path", "a/P.java", "text", P.replace("int x", "long x")))).contains("\"applied\":true");
        assertThat(ws.source("a/P.java")).contains("public long x;");
    }

    @Test
    void aFormAsksWhatARefactoringSuggests() {
        var capabilities = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int add(int a, long b) {
                        return a;
                    }
                }
                """)).capabilities();

        assertThat(capabilities.invoke("suggest", Map.of("refactoring", "change-signature", "args", Map.of("method", "a.P#add(int,long)"))))
                .contains("\"parameters\":\"int a, long b\"", "\"visibility\":\"package\"");
        assertThat(capabilities.invoke("suggest", Map.of("refactoring", "extract-superclass", "args", Map.of("type", "a.P"))))
                .isEqualTo("{\"name\":\"AbstractP\"}");
    }
}
