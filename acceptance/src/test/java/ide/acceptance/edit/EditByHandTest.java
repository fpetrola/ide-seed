package ide.acceptance.edit;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** An edit by hand is the user's text, and the IDE says what it understood of it: what the edit renamed or re-signed. */
class EditByHandTest {

    private static final String P = """
            package a;

            public class P {
                int x;

                int get(int scale) {
                    return x * scale;
                }
            }
            """;

    @Test
    void whatAnEditRenamesIsUnderstoodAsARename() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var result = ws.edit("a/P.java", P.replace("int x;", "int count;")
                .replace("int get(int scale)", "int total(long factor)").replace("x * scale", "count * factor"));

        assertThat(result.applied()).isTrue();
        assertThat(result.steps()).containsExactly(
                "' edit a/P.java",
                "  a.P#x -> a.P#count",
                "  a.P#get(int) -> a.P#total(long)",
                "  a.P#get(int)/scale -> a.P#total(long)/factor");
    }

    @Test
    void aTypeRenamedByHandIsOneRenameNotOnePerMember() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var result = ws.edit("a/P.java", P.replace("class P", "class Wallet"));

        assertThat(result.steps()).containsExactly("' edit a/P.java", "  a.P -> a.Wallet");
    }
}
