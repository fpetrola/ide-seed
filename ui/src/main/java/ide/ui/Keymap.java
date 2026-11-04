package ide.ui;

import javax.swing.KeyStroke;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The window's keys: IntelliJ's first, which the menus show, and Eclipse's too, so that whoever knows either finds
 * each thing where their hands expect it.
 */
final class Keymap {

    /** An action of the window, its name in a menu, the keys that do it (the first one shown), and what it does. */
    record Key(String action, String name, List<KeyStroke> strokes, String description) {

        /** The key shown; none for an action with no key. */
        KeyStroke stroke() {
            return strokes.isEmpty() ? null : strokes.getFirst();
        }
    }

    static final List<Key> KEYS = List.of(
            key("apply-edit", "Build Project", "Apply what the editors say to the program, and compile it", "ctrl S", "ctrl F9"),
            key("save", "Save All", "Write the changed files to disk", "ctrl shift S"),
            key("close-editor", "Close", "Close the editor, applying what it says first", "ctrl F4"),
            key("undo-change", "Undo Change", "Undo the last change to the program", "ctrl alt Z"),
            key("context-actions", "Show Context Actions", "Show context actions: the quick fixes of the line and the refactorings of the caret", "alt ENTER", "ctrl 1"),
            key("complete", "Basic Completion", "Complete the name being written", "ctrl SPACE"),
            key("generate", "Generate...", "Generate code into the type under the caret", "alt INSERT"),
            key("extend-selection", "Extend Selection", "Select what the selection is inside", "ctrl W", "alt shift UP"),
            key("shrink-selection", "Shrink Selection", "Select less again", "ctrl shift W", "alt shift DOWN"),
            key("find-action", "Find Action...", "Find an action by name", "ctrl shift A"),
            key("refactor", "Refactor This...", "Refactor this: the refactorings that take the element under the caret", "ctrl alt shift T", "alt shift T"),
            key("rename", "Rename...", "Rename the element under the caret", "shift F6", "alt shift R"),
            key("change-signature", "Change Signature...", "Change the signature of the method under the caret", "ctrl F6", "alt shift C"),
            key("move", "Move...", "Move the element under the caret", "F6", "alt shift V"),
            key("extract-method", "Extract Method...", "Extract the selected statements into a method", "ctrl alt M", "alt shift M"),
            key("introduce-constant", "Introduce Constant...", "Move the selected expression into a constant", "ctrl alt C"),
            key("introduce-field", "Introduce Field...", "Move the selected expression into a field", "ctrl alt F"),
            key("inline", "Inline...", "Inline the method or the local under the caret", "ctrl alt N", "alt shift I"),
            key("safe-delete", "Safe Delete...", "Delete the element under the caret, if nothing uses it", "alt DELETE"),
            key("open-declaration", "Declaration", "Go to where the element under the caret is declared", "ctrl B", "F3"),
            key("hierarchy", "Type Hierarchy", "Supertypes and subtypes of the type under the caret", "ctrl H", "F4"),
            key("callers", "Call Hierarchy", "Who calls the method under the caret", "ctrl alt H"),
            key("references", "Find Usages", "Every use of the element under the caret", "alt F7", "ctrl shift G"),
            key("open-type", "Class...", "Open a type by name", "ctrl N", "ctrl shift T"),
            key("outline", "File Structure", "Go to an element of this file", "ctrl F12", "ctrl O"),
            key("go-to-line", "Line...", "Go to a line", "ctrl G", "ctrl L"),
            key("next-problem", "Next Highlighted Error", "Go to the next problem", "F2", "ctrl PERIOD"),
            key("previous-problem", "Previous Highlighted Error", "Go to the previous problem", "shift F2", "ctrl COMMA"),
            key("back", "Back", "Go back to where the caret was", "ctrl alt LEFT", "alt LEFT"),
            key("forward", "Forward", "Go forward again", "ctrl alt RIGHT", "alt RIGHT"),
            key("terminal", "Terminal", "The terminal, in the project's folder", "alt F12"),
            key("flatten-packages", "Flatten Packages", "The project's packages each on one line, or nested as a tree of folders"),
            key("maximize-views", "Maximize Views", "The views below take the whole window; again, back", "ctrl shift QUOTE"),
            key("replay", "Replay Plan", "The plan shown, step by step as it was done: what each step takes, then what it changed", "ctrl alt shift R"),
            key("run", "Run Current File", "Runs the program of the current file's class, saved first", "ctrl shift F10"),
            key("run-chosen", "Run Configuration", "Runs the chosen run configuration", "shift F10"),
            key("rerun", "Rerun", "Runs again what ran last", "ctrl F5"),
            key("edit-configs", "Edit Configurations\u2026", "The run configurations: arguments, VM options, folder, environment", "alt shift F10"),
            key("stop-run", "Stop", "Stops the program running", "ctrl F2"),
            key("claude", "Claude Code", "Claude Code in the terminal, working on this IDE, going on with its conversation here", "ctrl ESCAPE"),
            key("claude-new", "Claude Code, New Conversation", "Claude Code in the terminal, working on this IDE, in a new conversation", "ctrl shift ESCAPE"),
            key("zoom-in", "Zoom In", "Bigger text, everywhere", "ctrl PLUS", "ctrl ADD", "ctrl shift EQUALS", "ctrl EQUALS"),
            key("zoom-out", "Zoom Out", "Smaller text", "ctrl MINUS", "ctrl SUBTRACT"),
            key("zoom-reset", "Reset Zoom", "Text at its usual size", "ctrl 0"),
            key("keys", "Keymap Reference", "Every key and what it does", "ctrl shift L"));

    private Keymap() {
    }

    static Optional<String> action(KeyStroke stroke) {
        return KEYS.stream().filter(key -> key.strokes().contains(stroke)).map(Key::action).findFirst();
    }

    /** Every key of every action. */
    static List<KeyStroke> strokes() {
        return KEYS.stream().flatMap(key -> key.strokes().stream()).toList();
    }

    static Key of(String action) {
        return KEYS.stream().filter(key -> key.action().equals(action)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no action " + action));
    }

    private static Key key(String action, String name, String description, String... strokes) {
        return new Key(action, name, Arrays.stream(strokes).map(KeyStroke::getKeyStroke).toList(), description);
    }
}
