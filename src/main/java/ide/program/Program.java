package ide.program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable view of the program: elements with stable identity and the references between them. */
public final class Program {

    private final Map<ElementId, Element> elements = new LinkedHashMap<>();
    private final List<Reference> references;

    public Program(Collection<Element> elements, List<Reference> references) {
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
    public Program retargeted(ElementId site, ElementId from, ElementId to) {
        var copy = new ArrayList<>(references);
        for (var i = 0; i < copy.size(); i++) {
            var r = copy.get(i);
            if (r.site().equals(site) && r.target().equals(from)) {
                copy.set(i, new Reference(r.id(), r.site(), to, r.name(), r.mode()));
                break;
            }
        }
        return new Program(elements.values(), copy);
    }

    /** A copy without the references made from inside {@code element} (it is going away with them). */
    public Program withoutUsesFrom(ElementId element) {
        var kept = references.stream().filter(r -> !inside(r.site(), element)).toList();
        return new Program(elements.values(), kept);
    }

    private boolean inside(ElementId id, ElementId container) {
        for (var e = elements.get(id); e != null; e = e.parent() == null ? null : elements.get(e.parent())) {
            if (e.id().equals(container)) return true;
        }
        return false;
    }

    /** A copy where one reference from {@code site} to {@code target} is made from {@code newSite} instead. */
    public Program resited(ElementId site, ElementId target, ElementId newSite) {
        var copy = new ArrayList<>(references);
        for (var i = 0; i < copy.size(); i++) {
            var r = copy.get(i);
            if (r.site().equals(site) && r.target().equals(target)) {
                copy.set(i, new Reference(r.id(), newSite, target, r.name(), r.mode()));
                break;
            }
        }
        return new Program(elements.values(), copy);
    }

    /** A copy without one reference from {@code site} to {@code target}. */
    public Program without(ElementId site, ElementId target) {
        var copy = new ArrayList<>(references);
        for (var i = 0; i < copy.size(); i++) {
            if (copy.get(i).site().equals(site) && copy.get(i).target().equals(target)) {
                copy.remove(i);
                break;
            }
        }
        return new Program(elements.values(), copy);
    }

    /** A hypothetical copy where one attribute of one element has a different value. */
    public Program with(ElementId id, String attribute, String value) {
        var copy = new LinkedHashMap<>(elements);
        var element = element(id);
        var attributes = new HashMap<>(element.attributes());
        attributes.put(attribute, value);
        copy.put(id, new Element(id, element.kind(), element.name(), element.parent(), element.qualified(),
                Map.copyOf(attributes)));
        return new Program(copy.values(), references);
    }

    /** The innermost type enclosing (or equal to) the element. */
    public Element enclosingType(ElementId id) {
        var element = element(id);
        while (!element.kind().isType()) element = element(element.parent());
        return element;
    }

    /** Whether {@code type} is {@code ancestor} or extends it, following {@code superclass}. */
    public boolean isSubclass(Element type, Element ancestor) {
        for (var t = Optional.of(type); t.isPresent(); t = Optional.ofNullable(t.get().attribute("superclass")).flatMap(this::find)) {
            if (t.get().id().equals(ancestor.id())) return true;
        }
        return false;
    }

    /** Direct supertypes in the workspace: the superclass, then the implemented (or extended) interfaces. */
    public List<Element> supertypes(Element type) {
        var names = new ArrayList<String>();
        if (type.attribute("superclass") != null) names.add(type.attribute("superclass"));
        if (type.attribute("implements") != null && !type.attribute("implements").isEmpty()) {
            names.addAll(List.of(type.attribute("implements").split(",")));
        }
        return names.stream().flatMap(n -> find(n).stream()).toList();
    }

    /** The method that {@code method} overrides first: nearest supertype first, superclass before interfaces. */
    public Optional<Element> overridden(Element method) {
        return overriddenAll(method).stream().findFirst();
    }

    /** Every method {@code method} directly overrides: on each supertype path, the nearest with the same signature. */
    public List<Element> overriddenAll(Element method) {
        var signature = signature(method);
        var found = new ArrayList<Element>();
        var visited = new java.util.HashSet<ElementId>();
        var pending = new java.util.ArrayDeque<>(supertypes(element(method.parent())));
        while (!pending.isEmpty()) {
            var type = pending.poll();
            if (!visited.add(type.id())) continue;
            var match = children(type.id()).stream()
                    .filter(e -> e.kind() == Kind.METHOD && signature(e).equals(signature)).findFirst();
            if (match.isPresent()) found.add(match.get());
            else pending.addAll(supertypes(type));
        }
        return found;
    }

    /** The method and every method related to it by overriding, transitively, up and down. */
    public List<Element> family(Element method) {
        var family = new ArrayList<Element>(List.of(method));
        for (var i = 0; i < family.size(); i++) {
            var current = family.get(i);
            overriddenAll(current).stream().filter(o -> !family.contains(o)).forEach(family::add);
            for (var other : methods()) {
                if (!family.contains(other) && overriddenAll(other).contains(current)) family.add(other);
            }
        }
        return family;
    }

    /** Direct children of an element, in declaration order. */
    public List<Element> children(ElementId parent) {
        return elements.values().stream().filter(e -> parent.equals(e.parent())).toList();
    }

    /** Fields then methods of a type, in declaration order. */
    public List<Element> members(ElementId type) {
        var fields = elements.values().stream().filter(e -> type.equals(e.parent()) && e.kind() == Kind.FIELD);
        var methods = elements.values().stream().filter(e -> type.equals(e.parent()) && e.kind() == Kind.METHOD);
        return java.util.stream.Stream.concat(fields, methods).toList();
    }

    /** The type and its superclasses in the workspace, nearest first. */
    public List<Element> superclassChain(ElementId type) {
        var chain = new ArrayList<Element>();
        for (var t = Optional.of(element(type)); t.isPresent(); t = Optional.ofNullable(t.get().attribute("superclass")).flatMap(this::find)) {
            chain.add(t.get());
        }
        return chain;
    }

    /** Top-level types declared in the source file {@code path}. */
    public List<Element> typesIn(String path) {
        return elements.values().stream().filter(e -> path.equals(e.attribute("file"))).toList();
    }

    public List<Element> types() {
        return elements.values().stream().filter(e -> e.kind().isType()).toList();
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
