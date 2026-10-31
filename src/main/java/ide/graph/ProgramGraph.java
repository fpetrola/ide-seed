package ide.graph;

import java.util.Collection;
import java.util.HashMap;
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

    /** A hypothetical copy where one attribute of one element has a different value. */
    public ProgramGraph with(ElementId id, String attribute, String value) {
        var copy = new LinkedHashMap<>(elements);
        var element = element(id);
        var attributes = new HashMap<>(element.attributes());
        attributes.put(attribute, value);
        copy.put(id, new Element(id, element.kind(), element.name(), element.parent(), element.qualified(),
                Map.copyOf(attributes)));
        return new ProgramGraph(copy.values(), references);
    }

    /** The innermost type enclosing (or equal to) the element. */
    public Element enclosingType(ElementId id) {
        var element = element(id);
        while (element.kind() != Kind.CLASS && element.kind() != Kind.INTERFACE) element = element(element.parent());
        return element;
    }

    /** Whether {@code type} is {@code ancestor} or extends it, following {@code superclass}. */
    public boolean isSubclass(Element type, Element ancestor) {
        for (var t = Optional.of(type); t.isPresent(); t = Optional.ofNullable(t.get().attribute("superclass")).flatMap(this::find)) {
            if (t.get().id().equals(ancestor.id())) return true;
        }
        return false;
    }

    /** The outermost type enclosing (or equal to) the element. */
    public Element topLevel(ElementId id) {
        var element = element(id);
        while (element.parent() != null && element(element.parent()).kind() != Kind.PACKAGE) {
            element = element(element.parent());
        }
        return element;
    }
}
