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
                "    ' set a.P#getX() body = return #{field:2}",
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

    /** A project opened from its folder is read from there, logs there, and is saved back there, only when asked. */
    @Test
    void aProjectOpenedFromAFolderIsSavedBackThere(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), P);
        Files.writeString(root.resolve("notes.txt"), "not a source");
        for (var built : java.util.List.of("target/generated", "build", "out", ".hidden")) { // what a build or a tool wrote
            Files.createDirectories(root.resolve(built));
            Files.writeString(root.resolve(built + "/Gen.java"), "class Gen {}");
        }

        var ws = Workspace.open(root);
        var capabilities = ws.capabilities();
        capabilities.invoke("rename", Map.of("element", "a.P#x", "name", "y"));

        assertThat(ws.files()).containsExactly("a/P.java");
        assertThat(Files.readString(root.resolve("a/P.java"))).isEqualTo(P);

        assertThat(capabilities.invoke("save", Map.of())).isEqualTo("\"ok\"");

        assertThat(Files.readString(root.resolve("a/P.java"))).contains("public int y;");
        assertThat(Files.readAllLines(root.resolve(".ide/events.jsonl"))).anyMatch(line -> line.contains("\"event\":\"save\""));
    }

    @Test
    void aWorkspaceInMemoryHasNowhereToSave() {
        var ws = Workspace.of(Map.of("a/P.java", P));

        org.assertj.core.api.Assertions.assertThatThrownBy(ws::save).hasMessageContaining("nowhere to save");
    }

    /** The libraries of a project in a Maven folder are what Maven says: its types bind and javac compiles against them. */
    @Test
    void aMavenProjectsLibrariesAreItsClasspath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>t</groupId>
                    <artifactId>t</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.github.javaparser</groupId>
                            <artifactId>javaparser-core</artifactId>
                            <version>3.26.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        var sources = Files.createDirectories(root.resolve("src/main/java/a"));
        Files.writeString(sources.resolve("U.java"), "package a;\n\nimport com.github.javaparser.ast.Node;\n\nclass U {\n    Node node;\n}\n");

        var ws = Workspace.open(root.resolve("src/main/java"));

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.model()).contains("a.U#node -> com.github.javaparser.ast.Node (declared)");
        assertThat(ws.capabilities().invoke("classpath", Map.of())).contains("javaparser-core-3.26.2.jar", "\"problem\":null");
    }

    @Test
    void aFolderOutOfAnyMavenProjectHasOnlyTheJdk(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), P);

        var classpath = Workspace.open(root).capabilities().invoke("classpath", Map.of());

        assertThat(classpath).startsWith("{\"entries\":[],\"problem\":\"no pom.xml in ");
    }
}
