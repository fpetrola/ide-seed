package ide.program;

import java.util.Map;

/**
 * A program element. {@code address} is where the text names it now (it changes with rename and move; {@code id}
 * does not) and {@code qualified} is that address rendered ({@code a.B}, {@code a.B#x}, {@code a.B#m(int)/p}).
 * {@code facts} are what else is known about it.
 */
public record Element(ElementId id, Kind kind, String name, ElementId parent, Address address, String qualified,
                      Map<Fact, String> facts) {

    public String fact(Fact fact) {
        return facts.get(fact);
    }
}
