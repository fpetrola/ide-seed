package ide.ui;

import org.junit.jupiter.api.Test;

import javax.swing.KeyStroke;

import static org.assertj.core.api.Assertions.assertThat;

/** The keys are IntelliJ's and Eclipse's: whoever uses either finds each thing where their hands expect it. */
class KeymapTest {

    @Test
    void eachKeyIsWhereIntelliJPutsIt() {
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt ENTER"))).contains("context-actions");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("shift F6"))).contains("rename");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl alt shift T"))).contains("refactor");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl alt M"))).contains("extract-method");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl B"))).contains("open-declaration");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt F7"))).contains("references");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl N"))).contains("open-type");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl W"))).contains("extend-selection");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt INSERT"))).contains("generate");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl shift A"))).contains("find-action");
        assertThat(Keymap.of("rename").stroke()).isEqualTo(KeyStroke.getKeyStroke("shift F6"));
    }

    @Test
    void andWhereEclipsePutsIt() {
        assertThat(Keymap.action(KeyStroke.getKeyStroke("F3"))).contains("open-declaration");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("F4"))).contains("hierarchy");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl 1"))).contains("context-actions");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt shift R"))).contains("rename");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt shift T"))).contains("refactor");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl shift G"))).contains("references");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl shift T"))).contains("open-type");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl O"))).contains("outline");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("alt shift UP"))).contains("extend-selection");
        assertThat(Keymap.action(KeyStroke.getKeyStroke("ctrl SPACE"))).contains("complete");
    }

    @Test
    void noKeyDoesTwoThings() {
        assertThat(Keymap.KEYS).extracting(Keymap.Key::action).doesNotHaveDuplicates();
        assertThat(Keymap.strokes()).doesNotHaveDuplicates();
    }
}
