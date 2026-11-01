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
}
