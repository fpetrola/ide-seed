package ide.graph;

import java.util.ArrayList;
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

    public Reference reference(String id) {
        return references.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no reference " + id));
    }

    public List<Reference> referencesTo(ElementId target) {
        return references.stream().filter(r -> r.target().equals(target)).toList();
    }

    /** A copy whose references from {@code site} to {@code from} point at {@code to} (one per call). */
    public ProgramGraph retargeted(ElementId site, ElementId from, ElementId to) {
        var copy = new ArrayList<>(references);
        for (var i = 0; i < copy.size(); i++) {
            var r = copy.get(i);
            if (r.site().equals(site) && r.target().equals(from)) {
                copy.set(i, new Reference(r.id(), r.site(), to, r.name(), r.mode()));
                break;
            }
        }
        return new ProgramGraph(elements.values(), copy);
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

    /** The method that {@code method} overrides: the nearest one with the same signature up the superclass chain. */
    public Optional<Element> overridden(Element method) {
        var signature = signature(method);
        var type = Optional.ofNullable(element(method.parent()).attribute("superclass")).flatMap(this::find);
        for (; type.isPresent(); type = Optional.ofNullable(type.get().attribute("superclass")).flatMap(this::find)) {
            var owner = type.get().id();
            var match = elements.values().stream()
                    .filter(e -> e.kind() == Kind.METHOD && owner.equals(e.parent()) && signature(e).equals(signature))
                    .findFirst();
            if (match.isPresent()) return match;
        }
        return Optional.empty();
    }

    /** The method and every method related to it by overriding, transitively, up and down. */
    public List<Element> family(Element method) {
        var family = new ArrayList<Element>(List.of(method));
        for (var i = 0; i < family.size(); i++) {
            var current = family.get(i);
            overridden(current).filter(o -> !family.contains(o)).ifPresent(family::add);
            for (var other : methods()) {
                if (!family.contains(other) && overridden(other).filter(current::equals).isPresent()) family.add(other);
            }
        }
        return family;
    }

    /** Top-level types declared in the source file {@code path}. */
    public List<Element> typesIn(String path) {
        return elements.values().stream().filter(e -> path.equals(e.attribute("file"))).toList();
    }

    public List<Element> types() {
        return elements.values().stream().filter(e -> e.kind() == Kind.CLASS || e.kind() == Kind.INTERFACE).toList();
    }

    public List<Element> methods() {
        return elements.values().stream().filter(e -> e.kind() == Kind.METHOD).toList();
    }

    private static String signature(Element method) {
        return method.qualified().substring(method.qualified().indexOf('#'));
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
