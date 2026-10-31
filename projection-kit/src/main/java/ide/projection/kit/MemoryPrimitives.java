package ide.projection.kit;

import ide.program.Address;
import ide.program.Change;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ide.projection.kit.MemoryGraph.duplicate;
import static ide.projection.kit.MemoryGraph.requireName;

/**
 * The primitives on a memory graph: what each does to elements and uses, refusing what a language driver would
 * refuse, and the change each declares (the primitive's intent, plus the uses an extract moved).
 */
final class MemoryPrimitives {

    private MemoryPrimitives() {
    }

    static Change apply(MemoryGraph graph, Primitive primitive, Program before) {
        return switch (primitive) {
            case Primitive.Create c -> {
                var created = create(graph, c.kind(), c.parent(), c.name(), c.argument());
                c.facts().forEach((fact, value) -> set(graph, created, fact.label(), value)); // born with them
                yield c.intent(before, created);
            }
            case Primitive.Delete d -> {
                delete(graph, d.element());
                yield d.intent(before, null);
            }
            case Primitive.Move m -> {
                move(graph, m.element(), m.parent(), m.before());
                yield m.intent(before, null);
            }
            case Primitive.Rename r -> {
                rename(graph, r.element(), r.name());
                yield r.intent(before, null);
            }
            case Primitive.Set s -> {
                set(graph, s.element(), s.attribute(), s.value());
                yield s.intent(before, null);
            }
            case Primitive.Link l -> {
                link(graph, l.element(), l.mode(), l.target());
                yield l.intent(before, null);
            }
            case Primitive.Retarget r -> {
                retarget(graph, r.reference(), r.target());
                yield r.intent(before, null);
            }
            case Primitive.Extract e -> extract(graph, e, before);
            case Primitive.Inline i -> {
                inline(graph, i.reference());
                yield i.intent(before, null);
            }
        };
    }

    private static ElementId create(MemoryGraph graph, Kind kind, ElementId parent, String name, String argument) {
        var ids = graph.ids;
        requireName(name, kind == Kind.PACKAGE);
        if (kind == Kind.PARAM && argument == null && graph.uses.stream().anyMatch(u -> u.target().equals(parent) && u.mode() == Mode.CALL)) {
            throw new PrimitiveRejected("create.argument", "the calls of " + graph.render(parent) + " need an argument for " + name);
        }
        Address address = switch (kind) {
            case PACKAGE -> new Address.Package(name);
            case CLASS, INTERFACE, ENUM -> new Address.Type(graph.render(parent) + "." + name);
            case FIELD -> new Address.Field(parent, name);
            case METHOD -> new Address.Callable(parent, name, List.of());
            case PARAM -> new Address.Param(parent, name);
            case CONSTRUCTOR -> throw new PrimitiveRejected("projection.unsupported", "constructors are not created");
        };
        if (ids.has(address)) throw duplicate(ids.render(address));
        var id = ids.of(address, kind);
        graph.add(id, new MemoryGraph.Node(kind, name, kind == Kind.PACKAGE ? null : parent, MemoryGraph.initialFacts(kind)));
        if (kind == Kind.PARAM) graph.resign(parent);
        return id;
    }

    private static void delete(MemoryGraph graph, ElementId element) {
        var doomed = graph.subtree(element);
        var users = new LinkedHashSet<String>();
        for (var use : graph.uses) if (doomed.contains(use.target()) && !doomed.contains(use.site())) users.add(graph.render(use.site()));
        if (!users.isEmpty()) throw new PrimitiveRejected("delete.referenced", graph.render(element) + " is used by " + users);
        var node = graph.nodes.get(element);
        graph.uses.removeIf(use -> doomed.contains(use.site()));
        graph.order.removeAll(doomed);
        doomed.forEach(graph.nodes::remove);
        graph.ids.forget(element);
        if (node.kind() == Kind.PARAM) graph.resign(node.parent());
    }

    private static void move(MemoryGraph graph, ElementId element, ElementId parent, ElementId before) {
        var ids = graph.ids;
        var node = graph.nodes.get(element);
        if (node.kind().isType()) {
            var target = graph.nodes.get(parent);
            if (target.kind() != Kind.PACKAGE) throw new PrimitiveRejected("projection.unsupported", "types move to packages");
            var now = target.name() + "." + node.name();
            if (ids.has(new Address.Type(now))) throw duplicate(now);
            ids.rebindType(((Address.Type) ids.address(element)).qualified(), now);
        } else if (node.kind() == Kind.PARAM) {
            if (!parent.equals(node.parent())) throw new PrimitiveRejected("projection.unsupported", "parameters move within their method");
        } else {
            var moved = ids.address(element).withOwner(parent);
            if (ids.has(moved)) throw duplicate(ids.render(moved));
            ids.move(element, parent);
        }
        graph.nodes.put(element, node.under(parent));
        graph.order.remove(element);
        graph.order.add(before == null ? graph.order.size() : graph.order.indexOf(before), element);
        if (node.kind() == Kind.PARAM) graph.resign(parent);
    }

    private static void rename(MemoryGraph graph, ElementId element, String name) {
        var ids = graph.ids;
        requireName(name, false);
        var node = graph.nodes.get(element);
        switch (node.kind()) {
            case PACKAGE -> throw new PrimitiveRejected("projection.unsupported", "a package is renamed by moving its types");
            case CONSTRUCTOR -> throw new PrimitiveRejected("projection.unsupported", "a constructor is named after its type");
            case CLASS, INTERFACE, ENUM -> {
                var old = ((Address.Type) ids.address(element)).qualified();
                var now = old.substring(0, old.lastIndexOf('.') + 1) + name;
                if (ids.has(new Address.Type(now))) throw duplicate(now);
                ids.rebindType(old, now);
                renameMentionsOf(graph, element, node.name(), name);
            }
            default -> {
                var renamed = ids.address(element).withName(name);
                if (ids.has(renamed)) throw duplicate(ids.render(renamed));
                ids.rename(element, name);
            }
        }
        graph.nodes.put(element, graph.nodes.get(element).named(name));
        graph.uses.replaceAll(use -> use.target().equals(element) ? new MemoryGraph.Use(use.site(), use.target(), name, use.mode()) : use);
    }

    /** A renamed type: its constructors are named after it, and the types written by its users name it anew. */
    private static void renameMentionsOf(MemoryGraph graph, ElementId type, String old, String name) {
        var word = Pattern.compile("\\b" + Pattern.quote(old) + "\\b");
        var users = new LinkedHashSet<ElementId>();
        for (var use : graph.uses) if (use.target().equals(type)) users.add(use.site());
        for (var id : graph.order) {
            var node = graph.nodes.get(id);
            if (node.kind() == Kind.CONSTRUCTOR && type.equals(node.parent())) {
                graph.nodes.put(id, node.named(name));
                graph.uses.replaceAll(use -> use.target().equals(id) ? new MemoryGraph.Use(use.site(), id, name, use.mode()) : use);
            }
            if (!users.contains(id)) continue;
            for (var fact : List.of(Fact.TYPE, Fact.SUPERCLASS)) {
                var text = graph.nodes.get(id).facts().get(fact);
                if (text != null) graph.nodes.put(id, graph.nodes.get(id).with(fact, word.matcher(text).replaceAll(Matcher.quoteReplacement(name))));
            }
        }
    }

    private static void set(MemoryGraph graph, ElementId element, String attribute, String value) {
        var fact = switch (attribute) {
            case "visibility", "static", "abstract", "final", "type", "superclass", "body" -> Fact.of(attribute);
            default -> throw new PrimitiveRejected("projection.unsupported", "a memory projection has no " + attribute);
        };
        var node = graph.nodes.get(element);
        graph.nodes.put(element, node.with(fact, value));
        if (fact == Fact.TYPE) graph.relink(element, Mode.DECLARED, graph.typeNamed(value)); // the text decides what it links to
        if (fact == Fact.SUPERCLASS) graph.relink(element, Mode.EXTENDS, graph.typeNamed(value));
        if (fact == Fact.TYPE && node.kind() == Kind.PARAM) graph.resign(node.parent());
    }

    private static void link(MemoryGraph graph, ElementId element, Mode mode, ElementId target) {
        graph.relink(element, mode, target);
        var node = graph.nodes.get(element);
        var name = graph.nodes.get(target).name();
        if (mode == Mode.EXTENDS) graph.nodes.put(element, node.with(Fact.SUPERCLASS, name));
        if (mode == Mode.DECLARED) {
            graph.nodes.put(element, node.with(Fact.TYPE, name));
            if (node.kind() == Kind.PARAM) graph.resign(node.parent());
        }
    }

    private static void retarget(MemoryGraph graph, String reference, ElementId target) {
        var index = graph.indexOf(reference);
        var use = graph.uses.get(index);
        var from = graph.nodes.get(use.target()).kind();
        var to = graph.nodes.get(target).kind();
        Mode mode;
        if (from == to || from.isType() && to.isType()) mode = use.mode();
        else if (to == Kind.METHOD && (use.mode() == Mode.READ || use.mode() == Mode.WRITE || use.mode() == Mode.NEW)) mode = Mode.CALL;
        else throw new PrimitiveRejected("retarget." + use.mode().label(), "cannot express a " + use.mode().label()
                    + " of " + graph.render(use.target()) + " with " + graph.render(target));
        graph.uses.set(index, new MemoryGraph.Use(use.site(), target, graph.nodes.get(target).name(), mode));
    }

    /** The fragment is a range of the method's uses by position: {@code "1-2"} is its second and third use. */
    private static Change extract(MemoryGraph graph, Primitive.Extract extract, Program before) {
        var range = extract.fragment().split("-");
        var positions = graph.usesFrom(extract.method());
        int first;
        int last;
        try {
            first = Integer.parseInt(range[0].strip());
            last = Integer.parseInt(range[range.length - 1].strip());
        } catch (NumberFormatException notARange) {
            throw new PrimitiveRejected("extract.fragment", "'" + extract.fragment() + "' is not a range of uses");
        }
        if (first < 0 || last < first || last >= positions.size()) {
            throw new PrimitiveRejected("extract.fragment", "uses " + extract.fragment() + " are not uses of " + graph.render(extract.method()));
        }
        requireName(extract.name(), false);
        var method = graph.nodes.get(extract.method());
        var address = new Address.Callable(method.parent(), extract.name(), List.of());
        if (graph.ids.has(address)) throw duplicate(graph.ids.render(address));
        var created = graph.ids.of(address, Kind.METHOD);
        graph.add(created, new MemoryGraph.Node(Kind.METHOD, extract.name(), method.parent(),
                MemoryGraph.typed(MemoryGraph.modifiers("private", method.facts().getOrDefault(Fact.STATIC, "false")), "void")));
        var change = extract.intent(before, created);
        for (var k = first; k <= last; k++) {
            var use = graph.uses.get(positions.get(k));
            graph.uses.set(positions.get(k), new MemoryGraph.Use(created, use.target(), use.name(), use.mode()));
            change = change.expect(new Change.Resited(use.target(), extract.method(), created));
        }
        graph.uses.add(positions.get(first), new MemoryGraph.Use(extract.method(), created, extract.name(), Mode.CALL));
        return change;
    }

    private static void inline(MemoryGraph graph, String reference) {
        var index = graph.indexOf(reference);
        var call = graph.uses.get(index);
        if (call.mode() != Mode.CALL || graph.nodes.get(call.target()).kind() != Kind.METHOD) {
            throw new PrimitiveRejected("inline.not-a-call", reference + " is not a call");
        }
        var body = graph.uses.stream().filter(use -> use.site().equals(call.target()) && !use.mode().isLink()).toList();
        if (body.stream().anyMatch(use -> use.target().equals(call.target()))) {
            throw new PrimitiveRejected("inline.recursive", graph.render(call.target()) + " calls itself");
        }
        graph.uses.remove(index);
        graph.uses.addAll(index, body.stream().map(use -> new MemoryGraph.Use(call.site(), use.target(), use.name(), use.mode())).toList());
    }
}
