package ide.refactor;

import ide.graph.Element;
import ide.graph.ElementId;
import ide.graph.ProgramGraph;

import java.util.List;
import java.util.Map;

/** Typed access to a refactoring's arguments; elements are given by id or by address and resolved against the graph. */
public record Args(Map<String, ?> values) {

    public String text(String name) {
        var value = values.get(name);
        if (!(value instanceof String text)) throw new IllegalArgumentException("missing text argument: " + name);
        return text;
    }

    public Element element(String name, ProgramGraph graph) {
        return resolve(text(name), graph);
    }

    public Element optionalElement(String name, ProgramGraph graph) {
        return values.get(name) == null ? null : element(name, graph);
    }

    public List<Element> elements(String name, ProgramGraph graph) {
        if (!(values.get(name) instanceof List<?> list)) throw new IllegalArgumentException("missing list argument: " + name);
        return list.stream().map(address -> resolve((String) address, graph)).toList();
    }

    static Element resolve(String idOrAddress, ProgramGraph graph) {
        var id = new ElementId(idOrAddress);
        if (graph.contains(id)) return graph.element(id);
        return graph.find(idOrAddress).orElseThrow(() -> new IllegalArgumentException("no element " + idOrAddress));
    }
}
