package ide.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A terminal as IntelliJ's: a real one (a program there knows it is in a terminal), in the project's folder. */
class TerminalTest {

    @TempDir
    Path root;

    @Test
    void aProgramRunsInTheProjectsFolderAndKnowsItIsInATerminal() throws Exception {
        var terminal = new Terminal(root);
        try {
            terminal.start(List.of("/bin/sh", "-c", "pwd; [ -t 0 ] && echo in-a-terminal; echo $TERM"));

            var shown = terminal.await(screen -> screen.contains("xterm-256color"), Duration.ofSeconds(10));

            assertThat(shown).contains(root.toRealPath().toString()).contains("in-a-terminal");
        } finally {
            terminal.close();
        }
    }

    @Test
    void whatIsTypedGoesToTheProgram() throws Exception {
        var terminal = new Terminal(root);
        try {
            terminal.start(List.of("/bin/sh", "-c", "read line; echo got:$line"));

            terminal.type("hello\r");

            assertThat(terminal.await(screen -> screen.contains("got:hello"), Duration.ofSeconds(10))).contains("got:hello");
        } finally {
            terminal.close();
        }
    }

    @Test
    void itStartsOnlyWhenItIsFirstShown() {
        var terminal = new Terminal(root);

        assertThat(terminal.running()).isFalse();
    }

    @Test
    void itKnowsWhichProgramsRunInIt() throws Exception {
        var terminal = new Terminal(root);
        try {
            terminal.start(List.of("/bin/sh", "-c", "sleep 30; echo done"));
            var until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!terminal.runs("sleep") && System.nanoTime() < until) Thread.sleep(50);

            assertThat(terminal.runs("sleep")).isTrue();
            assertThat(terminal.runs("claude")).isFalse();
        } finally {
            terminal.close();
        }
        assertThat(terminal.runs("sleep")).isFalse();
    }

    @Test
    void aClaudeStartedHereIsItsOwnNotAPartOfTheOneThatStartedTheIde() throws Exception {
        var terminal = new Terminal(root);
        terminal.environment("IDESEED_MCP_CONFIG", "kept");
        try {
            terminal.start(List.of("/bin/sh", "-c", "echo marks:$(env | grep -c -e ^CLAUDECODE -e ^CLAUDE_CODE_) config:$IDESEED_MCP_CONFIG"));

            assertThat(terminal.await(screen -> screen.contains("config:"), Duration.ofSeconds(10))).contains("marks:0 config:kept");
        } finally {
            terminal.close();
        }
    }

    @Test
    void aLineRunInAProgramIsTypedAndThenSent() throws Exception {
        var terminal = new Terminal(root);
        try {
            terminal.start(List.of("/bin/sh", "-c", "read line; echo got:$line"));
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try {
                    terminal.run("hello");
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });

            assertThat(terminal.await(screen -> screen.contains("got:hello"), Duration.ofSeconds(10))).contains("got:hello");
        } finally {
            terminal.close();
        }
    }
}
