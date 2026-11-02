package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptTest {

    private static final Map<String, String> SOURCES = Map.of(
            "a/A.java", """
                    package a;

                    public class A {
                    }
                    """,
            "a/B.java", """
                    package a;

                    public class B extends A {
                        protected int x;
                        protected int y;
                        int z;
                    }
                    """);

    @Test
    void definedScriptIsInvocableAndComposesOtherRefactorings() {
        var ws = Workspace.of(SOURCES);

        ws.define("""
                refactoring pull-up-protected-fields "Pulls up every protected field of a type"
                  param type : element(CLASS) "the type whose fields go up"
                  for f in fields(type) | with-attr "visibility=protected"
                    pull-up-field ${f}
                  end
                  return ${type}
                end
                """);
        var result = ws.invoke("pull-up-protected-fields", Map.of("type", "a.B"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/A.java")).contains("protected int x;", "protected int y;").doesNotContain("int z;");
        assertThat(result.steps()).containsExactly(
                ". pull-up-protected-fields",
                "  . pull-up-field",
                "    ' move a.B#x -> a.A",
                "  . pull-up-field",
                "    ' move a.B#y -> a.A");
    }

    /** A statement names elements; the engine checks that what the language wrote binds them, and nothing else. */
    @Test
    void aStatementWrittenSoThatItBindsSomethingElseIsNotKept() {
        var outer = """
                package a;

                public class Outer {
                    int x;

                    class Inner {
                        int x;

                        int get() {
                            return 0;
                        }
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Outer.java", outer));
        ws.define("""
                refactoring answer-field "Makes a method answer the value of a field"
                  param method : element(METHOD) "the method"
                  param field : element(FIELD) "the field it answers"
                  delete ${first(statements(method))}
                  create STATEMENT ${method} "" facts="body=return #{field}"
                  return ${method}
                end
                """);

        var result = ws.invoke("answer-field", Map.of("method", "a.Outer.Inner#get()", "field", "a.Outer#x"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code, Violation::detail).containsExactly(org.assertj.core.groups.Tuple.tuple(
                "binding.rebound", "a.Outer.Inner#get() now binds x to a.Outer.Inner#x instead of a.Outer#x"));
        assertThat(ws.source("a/Outer.java")).isEqualTo(outer);
    }

    @Test
    void requireAbortsWithItsMessage() {
        var ws = Workspace.of(SOURCES);
        ws.define("""
                refactoring only-interfaces
                  param type : element(CLASS)
                  require eq(kind(type), "INTERFACE") "${name(type)} is not an interface"
                end
                """);

        var result = ws.invoke("only-interfaces", Map.of("type", "a.B"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code, Violation::detail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("refactoring.precondition", "B is not an interface"));
    }

    @Test
    void catalogIsScriptsOverPrimitives() {
        var ws = Workspace.of(SOURCES);

        assertThat(ws.library())
                .contains("primitive create", "primitive move", "primitive rename", "primitive set", "primitive retarget",
                        "script encapsulate-field", "script extract-superclass", "script generate-getter",
                        "intent pull-up-field", "script rename-method-family", "script move-type");
        assertThat(ws.invoke("extract-superclass", Map.of("type", "a.B", "name", "Mid", "fields", List.of("a.B#z")))
                .applied()).isTrue();
    }

    @Test
    void atMostOneCompositeIsWrittenInJava() {
        assertThat(Workspace.of(Map.of()).library()).filteredOn(entry -> entry.startsWith("composite ")).hasSizeLessThanOrEqualTo(1);
    }
}
