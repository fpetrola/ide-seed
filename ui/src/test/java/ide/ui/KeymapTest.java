package ide.ui;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;

import static org.assertj.core.api.Assertions.assertThat;

/** The keys are Eclipse's: whoever uses Eclipse finds each thing where their hands expect it. */
class KeymapTest {

    @Test
    void eachKeyIsWhereEclipsePutsIt() {
        assertThat(Keymap.action(KeyStroke.getKeyStroke("F3"))).contains("open-declaration");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("F4"))).contains("hierarchy");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl 1"))).contains("quick-fix");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt shift R"))).contains("rename");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt shift T"))).contains("refactor");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl shift G"))).contains("references");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl alt H"))).contains("callers");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl shift T"))).contains("open-type");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl O"))).contains("outline");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl L"))).contains("go-to-line");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl SPACE"))).contains("complete");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl shift L"))).contains("keys");
        assertThat(Keymap.KEYS).extracting(Keymap.Key::action).doesNotHaveDuplicates();
    }
}
