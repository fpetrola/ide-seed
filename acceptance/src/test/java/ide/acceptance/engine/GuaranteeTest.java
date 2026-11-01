package ide.acceptance.engine;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuaranteeTest {

    private static final String P = """
            package a;

            class P {
                int x;
            }
            """;

    @Test
    void aVerifiedChangeOnCompilingCodeIsGuaranteed() {
        var result = Workspace.of(Map.of("a/P.java", P)).invoke("rename", Map.of("element", "a.P#x", "name", "y"));

        assertThat(result.guarantee()).isEqualTo("guaranteed");
        assertThat(result.obligations()).isEmpty();
    }

    @Test
    void onCodeThatDidNotCompileAChangeIsHeldToAddNoError() {
        var ws = Workspace.of(Map.of("a/P.java", P.replace("int x;", "int x;\n    Missing m;")));

        var result = ws.invoke("rename", Map.of("element", "a.P#x", "name", "y"));

        assertThat(result.applied()).isTrue();
        assertThat(result.guarantee()).isEqualTo("guaranteed");
        assertThat(ws.errors()).extracting(d -> d.code()).containsExactly("ecj.UndefinedType");
    }

    /**
     * An error anywhere in the project does not turn the compiler off: pulling up a blank final that its class's
     * constructor gives a value leaves the new superclass with no value for it, and a constructor assigning what is
     * not its own; both are new errors, so the change is undone.
     */
    @Test
    void anErrorElsewhereDoesNotLetAChangeBreakTheCode() {
        var unset = """
                package a;

                public class Unset {
                    private final int limit;

                    int twice() {
                        return limit * 2;
                    }

                    Unset(int limit) {
                        this.limit = limit;
                    }
                }
                """;
        var ws = Workspace.of(Map.of("a/Unset.java", unset, "b/Broken.java", """
                package b;

                class Broken {
                    int total() {
                        return count * 2;
                    }
                }
                """));

        var result = ws.invoke("extract-superclass", Map.of("type", "a.Unset", "name", "AbstractUnset",
                "fields", java.util.List.of("a.Unset#limit")));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(v -> v.code()).contains("ecj.UninitializedBlankFinalField");
        assertThat(ws.source("a/Unset.java")).isEqualTo(unset);
        assertThat(ws.files()).doesNotContain("a/AbstractUnset.java");
        assertThat(ws.errors()).extracting(d -> d.path()).containsExactly("b/Broken.java");
    }

    @Test
    void scriptsDeclareWhatTheyCannotDecideAndCompositesCollectIt() {
        var ws = Workspace.of(Map.of("a/P.java", P));
        ws.define("""
                refactoring rename-public "Renames, noting that code outside the workspace is not seen"
                  param element : element(FIELD,METHOD)
                  param name : text
                  oblige "no code outside the workspace uses ${qualified(element)}"
                  rename ${element} ${name}
                end

                refactoring rename-twice
                  param element : element(FIELD)
                  rename-public ${element} tmp
                  rename-public ${element} ${name(element)}2
                end
                """);

        var result = ws.invoke("rename-twice", Map.of("element", "a.P#x"));

        assertThat(result.guarantee()).isEqualTo("assumed");
        assertThat(result.obligations()).containsExactly(
                "no code outside the workspace uses a.P#x", "no code outside the workspace uses a.P#tmp");
    }
}
