package ide.program;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable view of the program: elements with stable identity and the references between them, uses and links
 * alike. Indexed by parent, by site, by target and by address, so that every query is local.
 */
public final class Program {

    /** What differs between two programs: references by (site, target), elements by id. */
    public record Diff(List<Reference> lost, List<Reference> gained, List<Element> born, List<Element> gone) {
    }

    private final Map<ElementId, Element> elements = new LinkedHashMap<>();
    private final List<Reference> references;
    private final Map<ElementId, List<Element>> children = new HashMap<>();
    private final Map<ElementId, List<Reference>> from = new HashMap<>();
    private final Map<ElementId, List<Reference>> to = new HashMap<>();
    private final Map<String, Reference> byReferenceId = new HashMap<>();
    private final Map<String, Element> byQualified = new HashMap<>();
    private final List<Element> types = new ArrayList<>();
    private final List<Element> methods = new ArrayList<>();
    private Map<ElementId, List<Element>> overriders;

    public Program(Collection<Element> elements, List<Reference> references) {
        for (var e : elements) {
            this.elements.put(e.id(), e);
            byQualified.putIfAbsent(e.qualified(), e);
            if (e.parent() != null) children.computeIfAbsent(e.parent(), k -> new ArrayList<>()).add(e);
            if (e.kind().isType()) types.add(e);
            if (e.kind() == Kind.METHOD) methods.add(e);
        }
        this.references = List.copyOf(references);
        for (var r : this.references) {
            from.computeIfAbsent(r.site(), k -> new ArrayList<>()).add(r);
            to.computeIfAbsent(r.target(), k -> new ArrayList<>()).add(r);
            byReferenceId.putIfAbsent(r.id(), r);
        }
    }

    // ---- elements

    public Collection<Element> elements() {
        return Collections.unmodifiableCollection(elements.values());
    }

    public Element element(ElementId id) {
        var element = elements.get(id);
        if (element == null) throw new IllegalArgumentException("no element " + id);
        return element;
    }

    public boolean contains(ElementId id) {
        return elements.containsKey(id);
    }

    /** The element at an address ({@code a.B}, {@code a.B#x}, {@code a.B#m(int)/p}). */
    public Optional<Element> find(String qualified) {
        return Optional.ofNullable(byQualified.get(qualified));
    }

    /** Direct children of an element, in declaration order. */
    public List<Element> children(ElementId parent) {
        return children.getOrDefault(parent, List.of());
    }

    /** Fields then methods of a type, in declaration order. */
    public List<Element> members(ElementId type) {
        var members = new ArrayList<Element>();
        for (var kind : List.of(Kind.FIELD, Kind.METHOD)) {
            for (var child : children(type)) if (child.kind() == kind) members.add(child);
        }
        return members;
    }

    public List<Element> types() {
        return Collections.unmodifiableList(types);
    }

    public List<Element> methods() {
        return Collections.unmodifiableList(methods);
    }

    /** Top-level types declared in the source file {@code path}. */
    public List<Element> typesIn(String path) {
        return types.stream().filter(e -> path.equals(e.fact(Fact.FILE))).toList();
    }

    /** The innermost type enclosing (or equal to) the element. */
    public Element enclosingType(ElementId id) {
        var element = element(id);
        while (!element.kind().isType()) element = element(element.parent());
        return element;
    }

    /** The outermost type enclosing (or equal to) the element. */
    public Element topLevel(ElementId id) {
        var element = element(id);
        while (element.parent() != null && element(element.parent()).kind() != Kind.PACKAGE) {
            element = element(element.parent());
        }
        return element;
    }

    // ---- references

    public List<Reference> references() {
        return references;
    }

    public Reference reference(String id) {
        var reference = byReferenceId.get(id);
        if (reference == null) throw new IllegalArgumentException("no reference " + id);
        return reference;
    }

    public List<Reference> referencesTo(ElementId target) {
        return to.getOrDefault(target, List.of());
    }

    public List<Reference> referencesFrom(ElementId site) {
        return from.getOrDefault(site, List.of());
    }

    // ---- links: the hierarchy and declared types are references too; the hierarchy is the workspace's

    /** The link of {@code mode} an element declares (what it extends, what it is declared with), wherever it binds. */
    public Optional<Reference> link(ElementId element, Mode mode) {
        return referencesFrom(element).stream().filter(r -> r.mode() == mode).findFirst();
    }

    /** The class a class extends, when it is in the workspace. */
    public Optional<Element> superclass(ElementId type) {
        if (element(type).kind() != Kind.CLASS) return Optional.empty();
        return link(type, Mode.EXTENDS).map(r -> element(r.target())).filter(e -> !e.inLibrary());
    }

    /** Direct supertypes in the workspace: what the type extends, then what it implements. */
    public List<Element> supertypes(Element type) {
        var supertypes = new ArrayList<Element>();
        for (var mode : List.of(Mode.EXTENDS, Mode.IMPLEMENTS)) {
            for (var r : referencesFrom(type.id())) {
                if (r.mode() == mode && !element(r.target()).inLibrary()) supertypes.add(element(r.target()));
            }
        }
        return supertypes;
    }

    /** Every supertype in the workspace, directly or not, nearest first. */
    public List<Element> ancestors(Element type) {
        var ancestors = new ArrayList<Element>();
        var seen = new HashSet<ElementId>();
        var pending = new ArrayDeque<>(supertypes(type));
        while (!pending.isEmpty()) {
            var next = pending.poll();
            if (!seen.add(next.id())) continue;
            ancestors.add(next);
            pending.addAll(supertypes(next));
        }
        return ancestors;
    }

    /** Direct subtypes in the workspace: the types that extend or implement {@code type}. */
    public List<Element> subtypes(Element type) {
        return referencesTo(type.id()).stream().filter(r -> r.mode() == Mode.EXTENDS || r.mode() == Mode.IMPLEMENTS)
                .map(r -> element(r.site())).distinct().toList();
    }

    /** The classes that extend {@code type} directly. */
    public List<Element> subclasses(ElementId type) {
        return referencesTo(type).stream().filter(r -> r.mode() == Mode.EXTENDS)
                .map(r -> element(r.site())).filter(e -> e.kind() == Kind.CLASS).toList();
    }

    /** The workspace type a field, parameter or method is declared with. */
    public Optional<Element> declaredType(ElementId element) {
        return link(element, Mode.DECLARED).map(r -> element(r.target())).filter(e -> !e.inLibrary());
    }

    /** The type and its superclasses in the workspace, nearest first. */
    public List<Element> superclassChain(ElementId type) {
        var chain = new ArrayList<Element>();
        for (var t = Optional.of(element(type)); t.isPresent(); t = superclass(t.get().id())) chain.add(t.get());
        return chain;
    }

    /** Whether {@code type} is {@code ancestor} or extends it, directly or not. */
    public boolean isSubclass(Element type, Element ancestor) {
        for (var t = Optional.of(type); t.isPresent(); t = superclass(t.get().id())) {
            if (t.get().id().equals(ancestor.id())) return true;
        }
        return false;
    }

    // ---- overriding

    /** The method that {@code method} overrides first: nearest supertype first, superclass before interfaces. */
    public Optional<Element> overridden(Element method) {
        return overriddenAll(method).stream().findFirst();
    }

    /** Every method {@code method} directly overrides: on each supertype path, the nearest with the same signature. */
    public List<Element> overriddenAll(Element method) {
        var signature = signature(method);
        var found = new ArrayList<Element>();
        var visited = new HashSet<ElementId>();
        var pending = new ArrayDeque<>(supertypes(element(method.parent())));
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

    /** Every method {@code method} overrides, directly or not: a call to any of them may dispatch to it. */
    public List<Element> overriddenTransitively(Element method) {
        var found = new ArrayList<Element>();
        var pending = new ArrayDeque<>(overriddenAll(method));
        while (!pending.isEmpty()) {
            var next = pending.poll();
            if (found.contains(next)) continue;
            found.add(next);
            pending.addAll(overriddenAll(next));
        }
        return found;
    }

    /** The methods that directly override {@code method}. */
    public List<Element> overriders(Element method) {
        if (overriders == null) {
            var index = new HashMap<ElementId, List<Element>>();
            for (var m : methods) {
                for (var o : overriddenAll(m)) index.computeIfAbsent(o.id(), k -> new ArrayList<>()).add(m);
            }
            overriders = index;
        }
        return overriders.getOrDefault(method.id(), List.of());
    }

    /** The method and every method related to it by overriding, transitively, up and down. */
    public List<Element> family(Element method) {
        var family = new ArrayList<Element>(List.of(method));
        for (var i = 0; i < family.size(); i++) {
            var current = family.get(i);
            overriddenAll(current).stream().filter(o -> !family.contains(o)).forEach(family::add);
            overriders(current).stream().filter(o -> !family.contains(o)).forEach(family::add);
        }
        return family;
    }

    /** Name and parameter type keys: what overriding compares. */
    private static Object signature(Element method) {
        var callable = (Address.Callable) method.address();
        return List.of(callable.name(), callable.keys());
    }

    // ---- comparison

    /** What {@code after} lost and gained with respect to this program. */
    public Diff diff(Program after) {
        var born = after.elements.values().stream().filter(e -> !elements.containsKey(e.id())).toList();
        var gone = elements.values().stream().filter(e -> !after.elements.containsKey(e.id())).toList();
        return new Diff(minus(references, after.references), minus(after.references, references), born, gone);
    }

    /** References in {@code a} with no counterpart (same site and target; the name may differ) in {@code b}. */
    private static List<Reference> minus(List<Reference> a, List<Reference> b) {
        var available = new HashMap<List<ElementId>, Integer>();
        b.forEach(ref -> available.merge(List.of(ref.site(), ref.target()), 1, Integer::sum));
        var result = new ArrayList<Reference>();
        for (var ref : a) {
            if (available.merge(List.of(ref.site(), ref.target()), -1, Integer::sum) < 0) result.add(ref);
        }
        return result;
    }

    /**
     * The program without ids, as sorted lines: {@code KIND address} for every type with its members and parameters,
     * {@code site -> target (mode)} for every reference. Two readings of the same source have the same outline.
     */
    public List<String> outline() {
        var lines = new ArrayList<String>();
        for (var type : types) outline(type, lines);
        for (var r : references) {
            lines.add(element(r.site()).qualified() + " -> " + element(r.target()).qualified() + " (" + r.mode().label() + ")");
        }
        lines.sort(null);
        return lines;
    }

    private void outline(Element element, List<String> lines) {
        lines.add(element.kind() + " " + element.qualified() + (element.inLibrary() ? " (library)" : ""));
        for (var child : children(element.id())) if (!child.kind().isType()) outline(child, lines);
    }

    // ---- what a verifier should expect after a change

    /** This program with every expectation of {@code change} applied: what the change means to leave. */
    public Program expecting(Change change) {
        var expected = this;
        for (var expectation : change.expectations()) expected = expected.expecting(expectation);
        return expected;
    }

    public Program expecting(Change.Expectation expectation) {
        return switch (expectation) {
            case Change.Retargeted r -> retargeted(r.site(), r.from(), r.to());
            case Change.Resited r -> resited(r.from(), r.target(), r.to());
            case Change.Dropped d -> without(d.site(), d.target());
            case Change.UsesGone g -> withoutUsesFrom(g.element());
        };
    }

    /** A copy whose first reference from {@code site} to {@code from} points at {@code to}. */
    private Program retargeted(ElementId site, ElementId from, ElementId to) {
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

    /** A copy where one reference from {@code site} to {@code target} is made from {@code newSite} instead. */
    private Program resited(ElementId site, ElementId target, ElementId newSite) {
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
    private Program without(ElementId site, ElementId target) {
        var copy = new ArrayList<>(references);
        for (var i = 0; i < copy.size(); i++) {
            if (copy.get(i).site().equals(site) && copy.get(i).target().equals(target)) {
                copy.remove(i);
                break;
            }
        }
        return new Program(elements.values(), copy);
    }

    /** A copy without the references made from inside {@code element} (it is going away with them). */
    private Program withoutUsesFrom(ElementId element) {
        var kept = references.stream().filter(r -> !inside(r.site(), element)).toList();
        return new Program(elements.values(), kept);
    }

    private boolean inside(ElementId id, ElementId container) {
        for (var e = elements.get(id); e != null; e = e.parent() == null ? null : elements.get(e.parent())) {
            if (e.id().equals(container)) return true;
        }
        return false;
    }
}
