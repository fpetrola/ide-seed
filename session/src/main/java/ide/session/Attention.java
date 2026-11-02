package ide.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Where attention is, both ways, between a person and an agent working on the same IDE: the last place an agent
 * pointed the person at (lines of a file, with a note; numbered, so whoever shows it knows it is new), and where the
 * person is looking, as the window last said. It knows nothing of programs: places are already resolved.
 */
final class Attention {

    /** A place pointed at: lines {@code line..endLine} of a file, the element they declare, if any, and a note. */
    record Pointed(int n, String path, int line, int endLine, String element, String note) {

        Map<String, Object> shown() {
            var shown = new LinkedHashMap<String, Object>();
            shown.put("n", n);
            shown.put("path", path);
            shown.put("line", line);
            shown.put("endLine", endLine);
            shown.put("element", element);
            shown.put("note", note);
            return shown;
        }
    }

    /** Where the person is: a file, the caret, and what is selected ({@code first-last} lines, or a stretch), if anything. */
    record Look(String path, int line, int column, String selected) {
        static final Look NOWHERE = new Look("", 0, 0, "");
    }

    private final Consumer<Map<String, Object>> events;
    private Pointed pointed = new Pointed(0, "", 0, 0, "", "");
    private Look look = Look.NOWHERE;

    Attention(Consumer<Map<String, Object>> events) {
        this.events = events;
    }

    Pointed point(String path, int line, int endLine, String element, String note) {
        pointed = new Pointed(pointed.n() + 1, path, line, Math.max(line, endLine), element, note);
        var event = new LinkedHashMap<String, Object>(pointed.shown());
        event.remove("n");
        event.put("event", "point");
        events.accept(event);
        return pointed;
    }

    Pointed pointed() {
        return pointed;
    }

    /** The person looks somewhere else; not an event: it changes at every step of the caret. */
    void look(Look look) {
        this.look = look;
    }

    Look looking() {
        return look;
    }
}
