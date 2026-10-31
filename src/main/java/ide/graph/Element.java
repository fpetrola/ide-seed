package ide.graph;

import java.util.Map;

/**
 * A program element. {@code qualified} is its current address ({@code a.B}, {@code a.B#x}, {@code a.B#m()}),
 * which changes with rename/move; {@code id} does not.
 */
public record Element(ElementId id, Kind kind, String name, ElementId parent, String qualified,
                      Map<String, String> attributes) {

    public String attribute(String name) {
        return attributes.get(name);
    }
}
