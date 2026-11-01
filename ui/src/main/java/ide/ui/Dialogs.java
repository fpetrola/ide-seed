package ide.ui;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** What the window asks people (to fill a form, to choose, to write a line) and what it tells them. */
interface Dialogs {

    /** The values of a form, or nothing if it was cancelled; {@code preview} answers what given values would change. */
    Optional<Map<String, Object>> fill(String title, Form form, Function<Map<String, Object>, String> preview);

    /** One of the options, or nothing. */
    Optional<String> choose(String title, List<String> options);

    /** A line of text, or nothing. */
    Optional<String> line(String question, String initial);

    void tell(String title, List<String> lines);
}
