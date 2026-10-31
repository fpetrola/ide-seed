package ide.program;

import java.util.Map;

/**
 * A program element. {@code qualified} is its current address ({@code a.B}, {@code a.B#x}, {@code a.B#m()}),
 * which changes with rename/move; {@code id} does not. {@code facts} are what else is known about it.
 */
public record Element(ElementId id, Kind kind, String name, ElementId parent, String qualified,
                      Map<Fact, String> facts) {

    public String fact(Fact fact) {
        return facts.get(fact);
    }
}
