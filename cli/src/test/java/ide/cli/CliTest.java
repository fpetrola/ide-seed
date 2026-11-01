package ide.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The IDE from a terminal: one command, a script, or a shell, over a project folder. */
class CliTest {

    private static final String P = """
            package a;

            public class P {
                public int x;
            }
            """;

    @TempDir
    Path root;

    private Path project() throws IOException {
        Files.createDirectories(root.resolve("a"));
        Files.writeString(root.resolve("a/P.java"), P);
        return root;
    }

    /** What the CLI writes, given its arguments and what it reads. */
    private static String run(String input, String... args) {
        var out = new StringWriter();
        new Cli(new BufferedReader(new StringReader(input)), new PrintWriter(out, true)).run(args);
        return out.toString();
    }

    @Test
    void oneCommandRunsAndWhatItChangedIsSaved() throws IOException {
        var out = run("", project().toString(), "rename", "a.P#x", "y");

        assertThat(out).contains("applied: true", "' rename a.P#x -> y");
        assertThat(Files.readString(root.resolve("a/P.java"))).contains("public int y;");
    }

    @Test
    void theShellRunsALineAtATimeAndSavesWhenAsked() throws IOException {
        var out = run("""
                rename element=a.P#x name=y
                show a/P.java
                undo
                exit
                rename a.P#x z
                """, project().toString());

        assertThat(out).contains("ide> ", "public int y;", "ok");
        assertThat(Files.readString(root.resolve("a/P.java"))).isEqualTo(P); // undone, never saved; nothing after exit
        assertThat(out).doesNotContain("public int z;");
    }

    @Test
    void aScriptRunsItsLinesInOrder() throws IOException {
        var script = Files.writeString(project().resolve("rename.ide"), """
                # rename, then keep it
                rename a.P#x count
                save
                """);

        run("", root.toString(), "--script", script.toString());

        assertThat(Files.readString(root.resolve("a/P.java"))).contains("public int count;");
    }

    @Test
    void helpIsTheSchemaOfEveryCapability() throws IOException {
        var out = run("", project().toString(), "help");

        assertThat(out).contains("rename element:element name:text — Renames an element and every use of it",
                "save — Writes the changed files where the project was opened from");
        assertThat(run("", root.toString(), "help", "show")).isEqualTo("show path:text — Source of a file\n");
    }

    @Test
    void aMistakeIsSaidAndTheShellGoesOn() throws IOException {
        var out = run("""
                nope
                rename a.P#nothing y
                ls
                """, project().toString());

        assertThat(out).contains("error: unknown capability: nope", "error: no element a.P#nothing", "a/P.java");
    }
}
