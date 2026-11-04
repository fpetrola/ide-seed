package ide.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** What is chosen of how the window shows things is there for the next window. */
class ShownTest {

    @TempDir
    Path home;

    @Test
    void aChoiceIsAsUsualUntilChosenAndThenKeptForTheNextWindow() {
        var shown = new Shown(home.resolve("shown.properties"));
        assertThat(shown.is("underline-errors", false)).isFalse();

        shown.set("underline-errors", true);

        assertThat(new Shown(home.resolve("shown.properties")).is("underline-errors", false)).isTrue();
        assertThat(new Shown(home.resolve("shown.properties")).is("flatten-packages", true)).isTrue();
    }
}
