package ide.acceptance.engine;

import ide.engine.Violation;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompilerViewTest {

    @Test
    void whatTheGraphViewsMissTheCompilerCatches() {
        var p = """
                package a;

                class P {
                    int one() {
                        return 1;
                    }

                    int two() {
                        return 2;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/P.java", p));

        // hashCode() now overrides Object's, which the graph does not hold, with weaker access
        var result = ws.invoke("rename", Map.of("element", "a.P#one()", "name", "hashCode"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("javac.override.weaker.access");
        assertThat(ws.source("a/P.java")).isEqualTo(p);
    }

    @Test
    void codeThatDidNotCompileBeforeIsNotHeldToCompileAfter() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    Missing m;
                    int x;
                }
                """));

        var result = ws.invoke("rename", Map.of("element", "a.P#x", "name", "y"));

        assertThat(result.applied()).isTrue();
    }

    @Test
    void compilerErrorsAreRepairedFromKnowledgeLikeAnyViolation() {
        var ws = Workspace.of(Map.of("a/P.java", """
                package a;

                class P {
                    int x;
                }
                """));
        ws.define("""
                refactoring add-names "Adds a list of names, without caring about imports"
                  param type : element(CLASS)
                  f := create FIELD ${type} names
                  set ${f} type List<String>
                end
                """);

        var result = ws.invoke("add-names", Map.of("type", "a.P"));

        assertThat(result.violations()).isEmpty();
        assertThat(result.repairs()).containsExactly("import java.util.List");
        assertThat(ws.source("a/P.java")).contains("import java.util.List;", "List<String> names;");
    }
}
