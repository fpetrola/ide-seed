package ide.ui;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the window asks people and tells them, the way IDEs do: small popups at the caret (a quick fix, a refactoring
 * menu, a new name), search popups at the top (a type, an element of the file), and a dialog only for a refactoring's
 * form, with its preview. A popup shows {@code at} a point of the screen, or at the top of the window when it has none.
 */
interface Dialogs {

    /** Something offered: what it says, the kind of thing it is (its icon), and a remark shown in gray. */
    record Option(String text, String kind, String remark) {

        static Option of(String text, String kind) {
            return new Option(text, kind, "");
        }
    }

    /** The values of a form, or nothing if it was cancelled; {@code preview} answers what given values would change. */
    Optional<Map<String, Object>> fill(String title, Form form, Function<Map<String, Object>, String> preview);

    /** One of the options, by its text, or nothing. */
    Optional<String> choose(String title, List<Option> options, Point at);

    /** A line of text, starting as {@code initial}, or nothing. */
    Optional<String> line(String title, String initial, Point at);

    /** Lines to read, until people go on. */
    void tell(String title, List<String> lines, Point at);
}
