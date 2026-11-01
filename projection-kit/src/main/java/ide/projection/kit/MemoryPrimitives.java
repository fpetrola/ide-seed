package ide.projection.kit;

import ide.program.Address;
import ide.program.Body;
import ide.program.Change;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Reference;
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
        PrimitiveRejected.unlessInWorkspace(before, primitive);
        return switch (primitive) {
            case Primitive.Create c when c.kind() == Kind.STATEMENT -> c.intent(before, statement(graph, c.parent(), c.facts().get(Fact.BODY)));
            case Primitive.Create c -> {
                var created = create(graph, c.kind(), c.parent(), c.name(), c.argument());
                c.facts().forEach((fact, value) -> set(graph, created, fact.label(), value)); // born with them
                yield c.intent(before, created);
            }
            case Primitive.Delete d -> {
                PrimitiveRejected.unlessMergeable(before, d);
                delete(graph, d.element(), d.into());
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
        if (kind != Kind.STATEMENT) requireName(name, kind == Kind.PACKAGE); // a statement has no name
        if (kind == Kind.PARAM && argument == null && graph.uses.stream().anyMatch(u -> parent.equals(u.target()) && u.mode() == Mode.CALL)) {
            throw new PrimitiveRejected("create.argument", "the calls of " + graph.render(parent) + " need an argument for " + name);
        }
        Address address = switch (kind) {
            case PACKAGE -> new Address.Package(name);
            case CLASS, INTERFACE, ENUM -> new Address.Type(graph.render(parent) + "." + name);
            case FIELD -> new Address.Field(parent, name);
            case METHOD -> new Address.Callable(parent, name, List.of());
            case PARAM -> new Address.Param(parent, name);
            case LOCAL -> new Address.Local(parent, name, (int) graph.nodes.values().stream()
                    .filter(n -> n.kind() == Kind.LOCAL && parent.equals(n.parent()) && n.name().equals(name)).count());
            case STATEMENT -> new Address.Statement(parent, graph.statements(parent).size()); // the last of its body
            case CONSTRUCTOR -> new Address.Callable(parent, "new", List.of());
        };
        if (ids.has(address)) throw duplicate(ids.render(address));
        var id = ids.of(address, kind);
        graph.add(id, new MemoryGraph.Node(kind, name, kind == Kind.PACKAGE ? null : parent, MemoryGraph.initialFacts(kind)));
        if (kind == Kind.PARAM) graph.resign(parent);
        return id;
    }

    /** A statement at the end of a body, said in no language: the uses it names are made in it. */
    private static ElementId statement(MemoryGraph graph, ElementId method, String said) {
        var body = said == null ? null : Body.parse(said, graph.read(), method);
        if (body != null && (body.statements().size() != 1 || body.keeps())) {
            throw new PrimitiveRejected("create.statement", "'" + said + "' is not one statement");
        }
        var statement = create(graph, Kind.STATEMENT, method, "", null);
        if (body == null) return statement;
        for (var use : Body.uses(body.statements().getFirst())) {
            graph.uses.add(new MemoryGraph.Use(method, use.element(), graph.nodes.get(use.element()).name(), use.mode(), use.receiver()).in(statement));
        }
        graph.nodes.put(statement, graph.nodes.get(statement).with(Fact.BODY, said));
        return statement;
    }

    /** An element nothing uses goes; one deleted {@code into} another leaves its uses to it first. */
    private static void delete(MemoryGraph graph, ElementId element, ElementId into) {
        var doomed = graph.subtree(element);
        if (into != null) {
            var name = graph.nodes.get(into).name();
            graph.uses.replaceAll(use -> element.equals(use.target()) && !doomed.contains(use.site())
                    ? use.moved(use.site(), into, name) : use);
        }
        var users = new LinkedHashSet<String>();
        for (var use : graph.uses) if (doomed.contains(use.target()) && !doomed.contains(use.site())) users.add(graph.render(use.site()));
        if (!users.isEmpty()) throw new PrimitiveRejected("delete.referenced", graph.render(element) + " is used by " + users);
        var node = graph.nodes.get(element);
        graph.uses.removeIf(use -> doomed.contains(use.site()) || use.statement() != null && doomed.contains(use.statement()));
        graph.order.removeAll(doomed);
        doomed.forEach(graph.nodes::remove);
        graph.ids.forget(element);
        if (node.kind() == Kind.PARAM) graph.resign(node.parent());
        if (node.kind() == Kind.STATEMENT) graph.ids.placeStatements(node.parent(), graph.statements(node.parent())); // those after close ranks
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
        } else if (node.kind() == Kind.STATEMENT) {
            if (before != null && !parent.equals(graph.nodes.get(before).parent())) {
                throw new PrimitiveRejected("move.before", graph.render(before) + " is not a statement of " + graph.render(parent));
            }
        } else {
            var moved = ids.address(element).withOwner(parent);
            if (ids.has(moved)) throw duplicate(ids.render(moved));
            ids.move(element, parent);
        }
        graph.nodes.put(element, node.under(parent));
        graph.order.remove(element);
        graph.order.add(before == null ? graph.order.size() : graph.order.indexOf(before), element);
        if (node.kind() == Kind.PARAM) graph.resign(parent);
        if (node.kind() == Kind.STATEMENT) { // its place among the body's statements, and the uses made in it, go with it
            graph.ids.placeStatements(parent, graph.statements(parent));
            if (!parent.equals(node.parent())) graph.ids.placeStatements(node.parent(), graph.statements(node.parent()));
            graph.uses.replaceAll(use -> element.equals(use.statement()) ? use.moved(parent, use.target(), use.name()) : use);
        }
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
        graph.uses.replaceAll(use -> element.equals(use.target()) ? use.moved(use.site(), use.target(), name) : use);
    }

    /** A renamed type: its constructors are named after it, and the types written by its users name it anew. */
    private static void renameMentionsOf(MemoryGraph graph, ElementId type, String old, String name) {
        var word = Pattern.compile("\\b" + Pattern.quote(old) + "\\b");
        var users = new LinkedHashSet<ElementId>();
        for (var use : graph.uses) if (type.equals(use.target())) users.add(use.site());
        for (var id : graph.order) {
            var node = graph.nodes.get(id);
            if (node.kind() == Kind.CONSTRUCTOR && type.equals(node.parent())) {
                graph.nodes.put(id, node.named(name));
                graph.uses.replaceAll(use -> id.equals(use.target()) ? use.moved(use.site(), id, name) : use);
            }
            if (!users.contains(id)) continue;
            for (var fact : List.of(Fact.TYPE, Fact.SUPERCLASS)) {
                var text = graph.nodes.get(id).facts().get(fact);
                if (text != null) graph.nodes.put(id, graph.nodes.get(id).with(fact, word.matcher(text).replaceAll(Matcher.quoteReplacement(name))));
            }
        }
    }

    private static void set(MemoryGraph graph, ElementId element, String attribute, String value) {
        if (attribute.equals(Fact.BODY.label())) {
            body(graph, element, value);
            return;
        }
        if (attribute.equals("initializer") && Body.names(value)) { // a start said in no language: its uses are the field's
            var start = Body.value(value, graph.read(), element);
            graph.uses.removeIf(use -> use.site().equals(element) && !use.mode().isLink());
            for (var use : Body.uses(start)) {
                graph.uses.add(new MemoryGraph.Use(element, use.element(), graph.nodes.get(use.element()).name(), use.mode(), use.receiver()));
            }
            graph.nodes.put(element, graph.nodes.get(element).with(Fact.INITIALIZED, "true"));
            return;
        }
        var fact = switch (attribute) {
            case "visibility", "static", "abstract", "final", "type", "superclass" -> Fact.of(attribute);
            default -> throw new PrimitiveRejected("projection.unsupported", "a memory projection has no " + attribute);
        };
        var node = graph.nodes.get(element);
        graph.nodes.put(element, node.with(fact, value));
        if (fact == Fact.TYPE) graph.relink(element, Mode.DECLARED, graph.typeNamed(value)); // the text decides what it links to
        if (fact == Fact.SUPERCLASS) graph.relink(element, Mode.EXTENDS, graph.typeNamed(value));
        if (fact == Fact.TYPE && node.kind() == Kind.PARAM) graph.resign(node.parent());
    }

    /**
     * A body in no language is its own text, and the uses and statements it makes take the place of the old body's; one
     * that keeps the old body ({@code ...}) is the old text followed by what it adds, and its uses and statements are added.
     */
    private static void body(MemoryGraph graph, ElementId method, String text) {
        var body = Body.parse(text, graph.read(), method);
        if (!body.keeps()) {
            graph.uses.removeIf(use -> use.site().equals(method) && !use.mode().isLink());
            for (var statement : graph.statements(method)) {
                graph.order.remove(statement);
                graph.nodes.remove(statement);
                graph.ids.forget(statement);
            }
        }
        for (var statement : body.statements()) {
            if (statement instanceof Body.Kept) continue;
            var made = create(graph, Kind.STATEMENT, method, "", null);
            graph.nodes.put(made, graph.nodes.get(made).with(Fact.BODY, Body.text(statement))); // what it says
            for (var use : Body.uses(statement)) {
                graph.uses.add(new MemoryGraph.Use(method, use.element(), graph.nodes.get(use.element()).name(), use.mode(), use.receiver()).in(made));
            }
        }
        var old = graph.nodes.get(method).facts().get(Fact.BODY);
        var said = body.keeps() && old != null ? old + "\n" + text.strip().substring(3).replaceFirst("^[;\\s]+", "") : text;
        graph.nodes.put(method, graph.nodes.get(method).with(Fact.BODY, said));
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

    /** A use that bound nothing binds {@code target}: a type use a type, a call a method, a read or write a field. */
    private static void bind(MemoryGraph graph, int index, MemoryGraph.Use use, ElementId target) {
        var kind = graph.nodes.get(target).kind();
        var fits = use.mode().isTypeUse() ? kind.isType() : use.mode() == Mode.CALL ? kind == Kind.METHOD
                : use.mode() == Mode.NEW ? kind.isType() : kind == Kind.FIELD;
        if (!fits) {
            throw new PrimitiveRejected("retarget." + use.mode().label(), "cannot bind a " + use.mode().label() + " of "
                    + use.name() + " to " + graph.render(target));
        }
        graph.uses.set(index, use.moved(use.site(), target, graph.nodes.get(target).name()));
        declare(graph, use.site(), use.mode(), target);
    }

    /** A declaration names {@code target} now: its type or superclass as written, and its method's signature. */
    private static void declare(MemoryGraph graph, ElementId site, Mode mode, ElementId target) {
        var name = graph.nodes.get(target).name();
        var node = graph.nodes.get(site);
        if (mode == Mode.DECLARED) {
            graph.nodes.put(site, node.with(Fact.TYPE, name));
            if (node.kind() == Kind.PARAM) graph.resign(node.parent());
        }
        if (mode == Mode.EXTENDS) graph.nodes.put(site, node.with(Fact.SUPERCLASS, name));
    }

    private static void retarget(MemoryGraph graph, String reference, ElementId target) {
        var index = graph.indexOf(reference);
        var use = graph.uses.get(index);
        if (use.target() == null) {
            bind(graph, index, use, target);
            return;
        }
        var from = graph.nodes.get(use.target()).kind();
        var to = graph.nodes.get(target).kind();
        Mode mode;
        if (from == to || from.isType() && to.isType()) mode = use.mode();
        else if (to == Kind.METHOD && (use.mode() == Mode.READ || use.mode() == Mode.WRITE || use.mode() == Mode.NEW)) mode = Mode.CALL;
        else if (to == Kind.FIELD && use.mode() == Mode.CALL) mode = Mode.READ; // a call of nothing but a value: the value
        else throw new PrimitiveRejected("retarget." + use.mode().label(), "cannot express a " + use.mode().label()
                    + " of " + graph.render(use.target()) + " with " + graph.render(target));
        var receiver = use.receiver() == Reference.Receiver.NONE ? Reference.Receiver.of(mode) : use.receiver();
        graph.uses.set(index, new MemoryGraph.Use(use.site(), target, graph.nodes.get(target).name(), mode, receiver).in(use.statement()));
        declare(graph, use.site(), mode, target);
    }

    /**
     * The fragment is statements of the method's body that follow one another, by their ids, or a range of the method's
     * uses by position: {@code "1-2"} is its second and third use.
     */
    private static Change extract(MemoryGraph graph, Primitive.Extract extract, Program before) {
        if (extract.fragment().contains(Kind.STATEMENT.prefix() + ":")) return extractStatements(graph, extract, before);
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
        var created = extracted(graph, extract);
        var change = extract.intent(before, created);
        var where = graph.uses.get(positions.get(first)).statement(); // the call goes where the fragment began
        for (var k = first; k <= last; k++) {
            var use = graph.uses.get(positions.get(k));
            graph.uses.set(positions.get(k), use.moved(created, use.target(), use.name()).in(null)); // the new method's body has no statements yet
            if (use.target() != null) change = change.expect(new Change.Resited(use.target(), extract.method(), created));
        }
        graph.uses.add(positions.get(first), new MemoryGraph.Use(extract.method(), created, extract.name(), Mode.CALL).in(where));
        return change;
    }

    /** The new private method an extraction makes, next to the method, static if it is. */
    private static ElementId extracted(MemoryGraph graph, Primitive.Extract extract) {
        requireName(extract.name(), false);
        var method = graph.nodes.get(extract.method());
        var address = new Address.Callable(method.parent(), extract.name(), List.of());
        if (graph.ids.has(address)) throw duplicate(graph.ids.render(address));
        var created = graph.ids.of(address, Kind.METHOD);
        graph.add(created, new MemoryGraph.Node(Kind.METHOD, extract.name(), method.parent(),
                MemoryGraph.typed(MemoryGraph.modifiers("private", method.facts().getOrDefault(Fact.STATIC, "false")), "void")));
        return created;
    }

    /**
     * Statements that follow one another move into the new method, keeping who they are, with the uses made in them;
     * a new statement calling it takes their place.
     */
    private static Change extractStatements(MemoryGraph graph, Primitive.Extract extract, Program before) {
        var method = extract.method();
        var own = graph.statements(method);
        var named = java.util.Arrays.stream(extract.fragment().split(",")).map(String::strip).map(ElementId::new).toList();
        var from = own.indexOf(named.getFirst());
        if (from < 0 || from + named.size() > own.size() || !own.subList(from, from + named.size()).equals(named)) {
            throw new PrimitiveRejected("extract.fragment", extract.fragment() + " are not statements of " + graph.render(method)
                    + " that follow one another");
        }
        var created = extracted(graph, extract);
        var change = extract.intent(before, created);
        for (var statement : named) graph.nodes.put(statement, graph.nodes.get(statement).under(created));
        graph.ids.placeStatements(created, named);
        var call = graph.ids.of(new Address.Statement(method, from), Kind.STATEMENT); // where the first one was
        graph.order.add(graph.order.indexOf(named.getFirst()), call);
        graph.nodes.put(call, new MemoryGraph.Node(Kind.STATEMENT, "", method, MemoryGraph.initialFacts(Kind.STATEMENT)));
        var left = new java.util.ArrayList<>(own.subList(0, from));
        left.add(call);
        left.addAll(own.subList(from + named.size(), own.size()));
        graph.ids.placeStatements(method, left);
        var first = -1;
        for (var i = 0; i < graph.uses.size(); i++) {
            var use = graph.uses.get(i);
            if (use.statement() == null || !named.contains(use.statement())) continue;
            if (first < 0) first = i;
            graph.uses.set(i, use.moved(created, use.target(), use.name()));
            if (use.target() != null) change = change.expect(new Change.Resited(use.target(), method, created));
        }
        var calling = new MemoryGraph.Use(method, created, extract.name(), Mode.CALL).in(call);
        if (first < 0) graph.uses.add(calling);
        else graph.uses.add(first, calling);
        return change;
    }

    private static void inline(MemoryGraph graph, String reference) {
        var index = graph.indexOf(reference);
        var call = graph.uses.get(index);
        if (call.target() == null || call.mode() != Mode.CALL || graph.nodes.get(call.target()).kind() != Kind.METHOD) {
            throw new PrimitiveRejected("inline.not-a-call", reference + " is not a call");
        }
        var body = graph.uses.stream().filter(use -> use.site().equals(call.target()) && !use.mode().isLink()).toList();
        if (body.stream().anyMatch(use -> call.target().equals(use.target()))) {
            throw new PrimitiveRejected("inline.recursive", graph.render(call.target()) + " calls itself");
        }
        graph.uses.remove(index);
        graph.uses.addAll(index, body.stream().map(use -> use.moved(call.site(), use.target(), use.name()).in(call.statement())).toList());
    }
}
