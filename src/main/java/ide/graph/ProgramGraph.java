package ide.graph;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable view of the program: elements with stable identity and the references between them. */
public final class ProgramGraph {

    private final Map<ElementId, Element> elements = new LinkedHashMap<>();
    private final List<Reference> references;

    public ProgramGraph(Collection<Element> elements, List<Reference> references) {
        elements.forEach(e -> this.elements.put(e.id(), e));
        this.references = List.copyOf(references);
    }

    public Element element(ElementId id) {
        var element = elements.get(id);
        if (element == null) throw new IllegalArgumentException("no element " + id);
        return element;
    }

    public boolean contains(ElementId id) {
        return elements.containsKey(id);
    }

    public Optional<Element> find(String qualified) {
        return elements.values().stream().filter(e -> e.qualified().equals(qualified)).findFirst();
    }

    public List<Reference> references() {
        return references;
    }

    /** The outermost type enclosing (or equal to) the element. */
    public Element topLevel(ElementId id) {
        var element = element(id);
        while (element.parent() != null) element = element(element.parent());
        return element;
    }
}
