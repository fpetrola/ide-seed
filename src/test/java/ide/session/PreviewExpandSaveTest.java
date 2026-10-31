package ide.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewExpandSaveTest {

    private static final String P = """
            package a;

            public class P {
                public int x;

                int get() {
                    return x;
                }
            }
            """;

    @Test
    void previewShowsTheDiffOfAVerifiedChangeAndLeavesTheProgramAsItWas() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var preview = ws.preview("rename", Map.of("element", "a.P#x", "name", "y"));

        assertThat(preview.result().applied()).isTrue();
        assertThat(preview.diff()).isEqualTo("""
                --- a/P.java
                +++ a/P.java
                @@ -4 +4 @@
                -    public int x;
                +    public int y;
                @@ -7 +7 @@
                -        return x;
                +        return y;
                """);
        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }

    @Test
    void expandShowsThePrimitivesWithoutApplyingThem() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        var steps = ws.expand("encapsulate-field", Map.of("field", "a.P#x"));

        assertThat(steps).contains(
                ". encapsulate-field",
                "  . generate-getter",
                "    ' create METHOD a.P#getX()",
                "    ' set a.P#getX() body = return #{field:2};",
                "  ' set a.P#x visibility = private");
        assertThat(ws.source("a/P.java")).isEqualTo(P);
    }

    @Test
    void nothingReachesDiskUntilSave(@TempDir Path root) throws Exception {
        var ws = Workspace.of(Map.of("a/P.java", P));
        ws.invoke("rename", Map.of("element", "a.P#x", "name", "y"));

        assertThat(ws.diff()).contains("+    public int y;");
        assertThat(root.resolve("a/P.java")).doesNotExist();

        ws.save(root);

        assertThat(Files.readString(root.resolve("a/P.java"))).contains("public int y;");
        assertThat(ws.diff()).isEmpty();
    }
}
