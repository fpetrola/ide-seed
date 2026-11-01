package ide.projection.kit;

import ide.program.Address;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Identity;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Program;
import ide.program.Reference;
import ide.program.Unresolved;
import ide.projection.PrimitiveRejected;
import ide.projection.Needs;
import ide.projection.Projection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The program a memory projection keeps, as plain data it changes in place: elements in declaration order, uses in
 * order (a reference's id is its site and its place among the site's uses), and identity.
 */
final class MemoryGraph {

    record Node(Kind kind, String name, ElementId parent, Map<Fact, String> facts) {

        Node named(String name) {
            return new Node(kind, name, parent, facts);
        }

        Node under(ElementId parent) {
            return new Node(kind, name, parent, facts);
        }

        Node with(Fact fact, String value) {
            var more = new EnumMap<Fact, String>(Fact.class);
            more.putAll(facts);
            more.put(fact, value);
            return new Node(kind, name, parent, Map.copyOf(more));
        }
    }

    /**
     * A use from {@code site}, in one of its statements or in none; its {@code target} is null when its name binds
     * nothing, and then it may say where it is looked up and what it needs.
     */
    record Use(ElementId site, ElementId target, String name, Mode mode, ElementId scope, Needs needs, Reference.Receiver receiver,
               ElementId statement) {

        Use(ElementId site, ElementId target, String name, Mode mode) {
            this(site, target, name, mode, null, null, Reference.Receiver.of(mode), null);
        }

        Use(ElementId site, ElementId target, String name, Mode mode, ElementId scope, Needs needs) {
            this(site, target, name, mode, scope, needs, Reference.Receiver.of(mode), null);
        }

        Use(ElementId site, ElementId target, String name, Mode mode, Reference.Receiver receiver) {
            this(site, target, name, mode, null, null, receiver, null);
        }

        /** The same use, made from {@code site}, binding {@code target} by {@code name}: on what it was made on, where it was. */
        Use moved(ElementId site, ElementId target, String name) {
            return new Use(site, target, name, mode, scope, needs, receiver, statement);
        }

        /** The same use, in {@code statement} (null: in none). */
        Use in(ElementId statement) {
            return new Use(site, target, name, mode, scope, needs, receiver, statement);
        }
    }

    record Saved(Identity.State ids, List<ElementId> order, Map<ElementId, Node> nodes, List<Use> uses)
            implements Projection.Snapshot {
    }

    private static final Pattern TOKEN = Pattern.compile("[\\w$.:]+");

    final Identity ids = new Identity();
    final List<ElementId> order = new ArrayList<>();
    final Map<ElementId, Node> nodes = new HashMap<>();
    final List<Use> uses = new ArrayList<>();

    Program read() {
        var elements = new ArrayList<Element>();
        for (var id : order) {
            var node = nodes.get(id);
            var address = ids.address(id);
            elements.add(new Element(id, node.kind(), node.name(), node.parent(), address, ids.render(address), node.facts()));
        }
        var references = new ArrayList<Reference>();
        var unresolved = new ArrayList<Unresolved>();
        var ordinals = new HashMap<ElementId, Integer>();
        for (var use : uses) {
            var id = use.site() + "@" + (ordinals.merge(use.site(), 1, Integer::sum) - 1);
            if (use.target() == null) unresolved.add(new Unresolved(id, use.site(), use.name(), use.mode(), use.scope(), use.statement()));
            else references.add(new Reference(id, use.site(), use.target(), use.name(), use.mode(), use.receiver(), use.statement()));
        }
        return new Program(elements, references, unresolved);
    }

    /** The use with an id as {@link #read()} gives it: its site and its place among the site's uses. */
    java.util.Optional<Use> use(String id) {
        var ordinals = new HashMap<ElementId, Integer>();
        for (var use : uses) {
            if (id.equals(use.site() + "@" + (ordinals.merge(use.site(), 1, Integer::sum) - 1))) return java.util.Optional.of(use);
        }
        return java.util.Optional.empty();
    }

    Saved save() {
        return new Saved(ids.save(), List.copyOf(order), Map.copyOf(nodes), List.copyOf(uses));
    }

    void restore(Saved saved) {
        ids.restore(saved.ids());
        order.clear();
        order.addAll(saved.order());
        nodes.clear();
        nodes.putAll(saved.nodes());
        uses.clear();
        uses.addAll(saved.uses());
    }

    /** Fills this (empty) graph with {@code program} under new ids. */
    void load(Program program) {
        var mapped = new HashMap<ElementId, ElementId>();
        // packages and types first, since callables key their parameter types by type id; what callables own last
        for (var pass : List.of(0, 1, 2)) {
            for (var e : program.elements()) {
                var rank = e.kind() == Kind.PACKAGE || e.kind().isType() ? 0 : e.kind() == Kind.PARAM || e.kind().inBody() ? 2 : 1;
                if (rank != pass) continue;
                var address = switch (e.address()) {
                    case Address.Package a -> a;
                    case Address.Type a -> a;
                    case Address.Field f -> new Address.Field(mapped.get(f.owner()), f.name());
                    case Address.Callable c -> new Address.Callable(mapped.get(c.owner()), c.name(),
                            c.keys().stream().map(key -> remap(key, mapped)).toList());
                    case Address.Param a -> new Address.Param(mapped.get(a.owner()), a.name());
                    case Address.Local a -> new Address.Local(mapped.get(a.owner()), a.name(), a.ordinal());
                    case Address.Statement a -> new Address.Statement(mapped.get(a.owner()), a.index());
                };
                mapped.put(e.id(), ids.of(address, e.kind()));
            }
        }
        for (var e : program.elements()) {
            var id = mapped.get(e.id());
            nodes.put(id, new Node(e.kind(), e.name(), e.parent() == null ? null : mapped.get(e.parent()), e.facts()));
            order.add(id);
        }
        for (var r : program.references()) {
            uses.add(new Use(mapped.get(r.site()), mapped.get(r.target()), r.name(), r.mode(), r.receiver()).in(mapped.get(r.statement())));
        }
        for (var u : program.unresolved()) {
            uses.add(new Use(mapped.get(u.site()), null, u.name(), u.mode(), u.scope() == null ? null : mapped.get(u.scope()), null)
                    .in(u.statement() == null ? null : mapped.get(u.statement())));
        }
    }

    private static String remap(String key, Map<ElementId, ElementId> mapped) {
        return TOKEN.matcher(key).replaceAll(m -> {
            var id = mapped.get(new ElementId(m.group()));
            return Matcher.quoteReplacement(id == null ? m.group() : id.value());
        });
    }

    // ---- what the primitives share

    void add(ElementId id, Node node) {
        nodes.put(id, node);
        order.add(id);
    }

    /** An element of a library: public, and marked so that no primitive changes it. */
    ElementId library(Address address, Kind kind, String name, ElementId parent) {
        var id = ids.of(address, kind);
        var facts = kind == Kind.PACKAGE ? Map.of(Fact.LIBRARY, "true") : Map.of(Fact.LIBRARY, "true", Fact.VISIBILITY, "public");
        add(id, new Node(kind, name, parent, facts));
        return id;
    }

    /** The statements of a callable's body, in order. */
    List<ElementId> statements(ElementId callable) {
        return order.stream().filter(id -> nodes.get(id).kind() == Kind.STATEMENT && callable.equals(nodes.get(id).parent())).toList();
    }

    /** A use made from {@code site}: of a member, on the object itself; of a local, on nothing. */
    void addUse(ElementId site, ElementId target, Mode mode) {
        var local = nodes.get(target).kind() == Kind.LOCAL;
        addUse(site, target, mode, local ? Reference.Receiver.NONE : Reference.Receiver.of(mode));
    }

    /** A use made from {@code site}; a statement as the site is the use's place in its method's body. */
    void addUse(ElementId site, ElementId target, Mode mode, Reference.Receiver receiver) {
        uses.add(placed(new Use(site, target, nodes.get(target).name(), mode, receiver)));
    }

    /** A use whose site is a statement is made from the statement's method, in that statement. */
    Use placed(Use use) {
        var node = nodes.get(use.site());
        return node.kind() == Kind.STATEMENT ? new Use(node.parent(), use.target(), use.name(), use.mode(), use.scope(), use.needs(),
                use.receiver(), use.site()) : use;
    }

    /** The element and everything inside it. */
    Set<ElementId> subtree(ElementId root) {
        var all = new LinkedHashSet<ElementId>(List.of(root));
        for (var grew = true; grew; ) {
            grew = false;
            for (var id : order) {
                if (!all.contains(id) && all.contains(nodes.get(id).parent())) grew = all.add(id);
            }
        }
        return all;
    }

    /** A callable's address follows the types of its parameters, in order. */
    void resign(ElementId callable) {
        var keys = new ArrayList<String>();
        for (var id : order) {
            var node = nodes.get(id);
            if (node.kind() == Kind.PARAM && callable.equals(node.parent())) keys.add(key(node.facts().get(Fact.TYPE)));
        }
        var current = (Address.Callable) ids.address(callable);
        if (current.keys().equals(keys)) return;
        var signed = new Address.Callable(current.owner(), current.name(), keys);
        if (ids.has(signed)) throw duplicate(ids.render(signed));
        ids.resign(callable, keys);
    }

    /** The element's link of {@code mode} targets {@code target} now (none if null); implements adds one more. */
    void relink(ElementId element, Mode mode, ElementId target) {
        var existing = -1;
        for (var i = 0; mode != Mode.IMPLEMENTS && i < uses.size(); i++) {
            if (uses.get(i).site().equals(element) && uses.get(i).mode() == mode) {
                existing = i;
                break;
            }
        }
        var use = target == null ? null : new Use(element, target, nodes.get(target).name(), mode);
        if (existing >= 0 && use == null) uses.remove(existing);
        else if (existing >= 0) uses.set(existing, use);
        else if (use != null) uses.add(linkPosition(element, mode), use);
    }

    /** Where a new link goes among its site's uses: what a type extends, then what it implements, then the rest. */
    private int linkPosition(ElementId site, Mode mode) {
        var first = -1;
        var afterLinks = -1;
        for (var i = 0; i < uses.size(); i++) {
            var use = uses.get(i);
            if (!use.site().equals(site)) continue;
            if (first < 0) first = i;
            if (mode == Mode.IMPLEMENTS && (use.mode() == Mode.EXTENDS || use.mode() == Mode.IMPLEMENTS)) afterLinks = i + 1;
        }
        return afterLinks >= 0 ? afterLinks : first >= 0 ? first : uses.size();
    }

    /** A parameter type key: the id of the workspace type the text names, or the text. */
    String key(String type) {
        var named = typeNamed(type);
        return named == null ? type : named.value();
    }

    /** The workspace type a text names, by qualified or (unique) simple name; null if none. */
    ElementId typeNamed(String text) {
        var qualified = ids.types().get(text);
        if (qualified != null) return qualified;
        var simple = ids.types().entrySet().stream().filter(e -> Address.simpleName(e.getKey()).equals(text))
                .map(Map.Entry::getValue).toList();
        return simple.size() == 1 ? simple.getFirst() : null;
    }

    /** The position in {@link #uses} of the reference with this id ({@code site@ordinal}). */
    int indexOf(String reference) {
        var at = reference.lastIndexOf('@');
        var site = new ElementId(reference.substring(0, at));
        var ordinal = Integer.parseInt(reference.substring(at + 1));
        var seen = 0;
        for (var i = 0; i < uses.size(); i++) {
            if (uses.get(i).site().equals(site) && seen++ == ordinal) return i;
        }
        throw new IllegalArgumentException("no reference " + reference);
    }

    /** The positions in {@link #uses} of the uses made from {@code site}, in order. */
    List<Integer> usesFrom(ElementId site) {
        var positions = new ArrayList<Integer>();
        for (var i = 0; i < uses.size(); i++) if (uses.get(i).site().equals(site)) positions.add(i);
        return positions;
    }

    String render(ElementId id) {
        return ids.render(ids.address(id));
    }

    /** What a language writes by default for a new element. */
    static Map<Fact, String> initialFacts(Kind kind) {
        return switch (kind) {
            case PACKAGE -> Map.of();
            case PARAM, LOCAL -> Map.of(Fact.TYPE, "Object");
            case STATEMENT -> Map.of();
            case CLASS, INTERFACE, ENUM -> modifiers("public", "false");
            case FIELD -> typed(modifiers("package", "false"), "Object");
            case METHOD, CONSTRUCTOR -> typed(modifiers("package", "false"), "void");
        };
    }

    static Map<Fact, String> modifiers(String visibility, String isStatic) {
        return Map.of(Fact.VISIBILITY, visibility, Fact.STATIC, isStatic, Fact.ABSTRACT, "false", Fact.FINAL, "false");
    }

    static Map<Fact, String> typed(Map<Fact, String> facts, String type) {
        var more = new EnumMap<Fact, String>(Fact.class);
        more.putAll(facts);
        more.put(Fact.TYPE, type);
        return Map.copyOf(more);
    }

    static void requireName(String name, boolean dotted) {
        if (name == null || name.isBlank() || name.chars().anyMatch(Character::isWhitespace) || !dotted && name.contains(".")) {
            throw new PrimitiveRejected("projection.invalid-identifier", "'" + name + "' is not a name");
        }
    }

    static PrimitiveRejected duplicate(String address) {
        return new PrimitiveRejected("projection.duplicate", address + " already exists");
    }
}
