package ide.engine;

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
    void onCodeThatDidNotCompileTypesAreAnAssumption() {
        var ws = Workspace.of(Map.of("a/P.java", P.replace("int x;", "int x;\n    Missing m;")));

        var result = ws.invoke("rename", Map.of("element", "a.P#x", "name", "y"));

        assertThat(result.applied()).isTrue();
        assertThat(result.guarantee()).isEqualTo("assumed");
        assertThat(result.obligations()).containsExactly("types were not checked: the code did not compile before");
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
