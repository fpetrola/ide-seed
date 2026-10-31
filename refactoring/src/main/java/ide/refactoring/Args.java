package ide.refactoring;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Program;

import java.util.List;
import java.util.Map;

/**
 * A refactoring's arguments, by name. Scripts give values; adapters give text, lists and maps. An element given as
 * text may be an id or an address: this is the one place that tells which.
 */
public record Args(Map<String, ?> values) {

    public Value value(String name) {
        return Value.of(values.get(name));
    }

    public String text(String name) {
        var value = value(name);
        if (value == null || value instanceof Value.Items) throw new IllegalArgumentException("missing text argument: " + name);
        return value.text();
    }

    public Element element(String name, Program program) {
        return element(value(name), name, program);
    }

    public Element optionalElement(String name, Program program) {
        return values.get(name) == null ? null : element(name, program);
    }

    public List<Element> elements(String name, Program program) {
        if (!(value(name) instanceof Value.Items list)) throw new IllegalArgumentException("missing list argument: " + name);
        return list.items().stream().map(item -> element(item, name, program)).toList();
    }

    private static Element element(Value value, String name, Program program) {
        return switch (value) {
            case Value.Elem e when program.contains(e.id()) -> program.element(e.id());
            case Value.Elem e -> throw new IllegalArgumentException("no element " + e.id().value());
            case Value.Text t -> resolve(t.text(), program);
            case null, default -> throw new IllegalArgumentException("missing element argument: " + name);
        };
    }

    private static Element resolve(String idOrAddress, Program program) {
        var id = new ElementId(idOrAddress);
        if (program.contains(id)) return program.element(id);
        return program.find(idOrAddress).orElseThrow(() -> new IllegalArgumentException("no element " + idOrAddress));
    }
}
