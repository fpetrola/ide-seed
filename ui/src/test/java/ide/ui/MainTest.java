package ide.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/** Opening a folder can fail; what went wrong is said, with what to open instead. With no folder, the last one opened. */
class MainTest {

    @Test
    void sourcesThatDoNotFitInMemorySayWhatToOpenInstead() {
        var said = Main.failure(new ExecutionException(new OutOfMemoryError("Java heap space")), Path.of("/work/oozx"));

        assertThat(said).contains("/work/oozx", "do not fit in memory", "src/main/java", "-Xmx");
    }

    @Test
    void anyOtherFailureIsItsMessage() {
        assertThat(Main.failure(new ExecutionException(new IllegalStateException("a/B.java: (line 3,col 1) Parse error")),
                Path.of("/work"))).isEqualTo("a/B.java: (line 3,col 1) Parse error");
    }

    @TempDir
    Path home;

    @Test
    void withNoFolderTheLastOneOpenedOpensAgain() throws Exception {
        var project = Files.createDirectories(home.resolve("work/test1"));
        assertThat(Main.last(home)).isEmpty();

        Main.opened(home, project);

        assertThat(Main.last(home)).contains(project.toAbsolutePath().normalize());
    }

    @Test
    void aLastFolderThatIsGoneIsForgotten() throws Exception {
        var project = Files.createDirectories(home.resolve("work/test1"));
        Main.opened(home, project);

        Files.delete(project);

        assertThat(Main.last(home)).isEmpty();
    }
}
