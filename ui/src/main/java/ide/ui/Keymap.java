package ide.ui;

import javax.swing.KeyStroke;
import java.util.List;
import java.util.Optional;

/** Eclipse's keys and what each does: whoever knows Eclipse finds each thing where their hands expect it. */
final class Keymap {

    /** An action of the window, the key that does it, and what it does. */
    record Key(String action, KeyStroke stroke, String description) {
    }

    static final List<Key> KEYS = List.of(
            key("apply-edit", "ctrl S", "Apply what the editor says to the program"),
            key("save", "ctrl shift S", "Write the changed files to disk"),
            key("undo-change", "ctrl alt Z", "Undo the last change to the program"),
            key("quick-fix", "ctrl 1", "Quick fixes for the problems on the line"),
            key("complete", "ctrl SPACE", "Complete the name being written"),
            key("rename", "alt shift R", "Rename the element under the caret"),
            key("refactor", "alt shift T", "Refactorings that take the element under the caret"),
            key("change-signature", "alt shift C", "Change the signature of the method under the caret"),
            key("move", "alt shift V", "Move the element under the caret"),
            key("open-declaration", "F3", "Go to where the element under the caret is declared"),
            key("hierarchy", "F4", "Supertypes and subtypes of the type under the caret"),
            key("callers", "ctrl alt H", "Who calls the method under the caret"),
            key("references", "ctrl shift G", "Every use of the element under the caret"),
            key("open-type", "ctrl shift T", "Open a type by name"),
            key("outline", "ctrl O", "Go to an element of this file"),
            key("go-to-line", "ctrl L", "Go to a line"),
            key("next-problem", "ctrl PERIOD", "Go to the next problem"),
            key("previous-problem", "ctrl COMMA", "Go to the previous problem"),
            key("back", "alt LEFT", "Go back to where the caret was"),
            key("forward", "alt RIGHT", "Go forward again"),
            key("zoom-in", "ctrl EQUALS", "Bigger text"),
            key("zoom-out", "ctrl MINUS", "Smaller text"),
            key("keys", "ctrl shift L", "Every key and what it does"));

    private Keymap() {
    }

    static Optional<String> action(KeyStroke stroke) {
        return KEYS.stream().filter(key -> key.stroke().equals(stroke)).map(Key::action).findFirst();
    }

    private static Key key(String action, String stroke, String description) {
        return new Key(action, KeyStroke.getKeyStroke(stroke), description);
    }
}
