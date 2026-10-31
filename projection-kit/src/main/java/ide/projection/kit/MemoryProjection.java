package ide.projection.kit;

import ide.program.Address;
import ide.program.Change;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Identity;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A projection with no language: the program is kept as a graph and each primitive changes the graph directly,
 * declaring the same change a language driver would. It is the reference implementation of
 * {@link ProjectionContract}, and the stand-in for a driver in the tests of the products above it.
 * <p>
 * What only a language knows is not modelled: there is no text (so no {@link #edit} and no diagnostics), uses bind by
 * id (so nothing is ever shadowed), a body is a fact whose uses are given with {@link Builder#use}. An extract's
 * fragment is a range of the method's uses by position: {@code "1-2"} is its second and third use.
 */
public final class MemoryProjection implements Projection {

    /** No access is ever restricted, every visibility exists, nothing can be imported. */
    public static final LanguageRules OPEN = new LanguageRules() {
        @Override
        public boolean accessible(Program program, ElementId site, ElementId target) {
            return true;
        }

        @Override
        public List<String> importCandidates(Program program, String simpleName) {
            return List.of();
        }

        @Override
        public List<String> visibilities() {
            return List.of("private", "package", "protected", "public");
        }
    };

    private record Node(Kind kind, String name, ElementId parent, Map<Fact, String> facts) {

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

    private record Use(ElementId site, ElementId target, String name, Mode mode) {
    }

    private record Saved(Identity.State ids, List<ElementId> order, Map<ElementId, Node> nodes, List<Use> uses)
            implements Snapshot {
    }

    private static final Pattern TOKEN = Pattern.compile("[\\w$.:]+");

    private final Identity ids = new Identity();
    private final List<ElementId> order = new ArrayList<>();
    private final Map<ElementId, Node> nodes = new HashMap<>();
    private final List<Use> uses = new ArrayList<>();
    private LanguageRules rules;
    private Program program;

    private MemoryProjection(LanguageRules rules) {
        this.rules = rules;
    }

    public static Builder builder() {
        return new Builder(new MemoryProjection(OPEN));
    }

    /** A projection holding {@code program}, read afresh: the same outline under new ids. */
    public static MemoryProjection of(Program program, LanguageRules rules) {
        var projection = new MemoryProjection(rules);
        var ids = new HashMap<ElementId, ElementId>();
        // packages and types first, since callables key their parameter types by type id; parameters last
        for (var pass : List.of(0, 1, 2)) {
            for (var e : program.elements()) {
                var rank = e.kind() == Kind.PACKAGE || e.kind().isType() ? 0 : e.kind() == Kind.PARAM ? 2 : 1;
                if (rank != pass) continue;
                var address = switch (e.address()) {
                    case Address.Package a -> a;
                    case Address.Type a -> a;
                    case Address.Field f -> new Address.Field(ids.get(f.owner()), f.name());
                    case Address.Callable c -> new Address.Callable(ids.get(c.owner()), c.name(),
                            c.keys().stream().map(key -> remap(key, ids)).toList());
                    case Address.Param a -> new Address.Param(ids.get(a.owner()), a.name());
                };
                ids.put(e.id(), projection.ids.of(address, e.kind()));
            }
        }
        for (var e : program.elements()) {
            var id = ids.get(e.id());
            projection.nodes.put(id, new Node(e.kind(), e.name(), e.parent() == null ? null : ids.get(e.parent()), e.facts()));
            projection.order.add(id);
        }
        for (var r : program.references()) projection.uses.add(new Use(ids.get(r.site()), ids.get(r.target()), r.name(), r.mode()));
        return projection;
    }

    private static String remap(String key, Map<ElementId, ElementId> ids) {
        return TOKEN.matcher(key).replaceAll(m -> {
            var mapped = ids.get(new ElementId(m.group()));
            return Matcher.quoteReplacement(mapped == null ? m.group() : mapped.value());
        });
    }

    // ---- the projection

    @Override
    public Program program() {
        if (program == null) program = read();
        return program;
    }

    private Program read() {
        var elements = new ArrayList<Element>();
        for (var id : order) {
            var node = nodes.get(id);
            var address = ids.address(id);
            elements.add(new Element(id, node.kind(), node.name(), node.parent(), address, ids.render(address), node.facts()));
        }
        var references = new ArrayList<Reference>();
        var ordinals = new HashMap<ElementId, Integer>();
        for (var use : uses) {
            var ordinal = ordinals.merge(use.site(), 1, Integer::sum) - 1;
            references.add(new Reference(use.site() + "@" + ordinal, use.site(), use.target(), use.name(), use.mode()));
        }
        return new Program(elements, references);
    }

    @Override
    public Change apply(Primitive primitive) {
        var before = program();
        var saved = snapshot();
        try {
            var change = switch (primitive) {
                case Primitive.Create c -> {
                    var created = create(c.kind(), c.parent(), c.name(), c.argument());
                    c.facts().forEach((fact, value) -> set(created, fact.label(), value)); // born with them
                    yield c.intent(before, created);
                }
                case Primitive.Delete d -> {
                    delete(d.element());
                    yield d.intent(before, null);
                }
                case Primitive.Move m -> {
                    move(m.element(), m.parent(), m.before());
                    yield m.intent(before, null);
                }
                case Primitive.Rename r -> {
                    rename(r.element(), r.name());
                    yield r.intent(before, null);
                }
                case Primitive.Set s -> {
                    set(s.element(), s.attribute(), s.value());
                    yield s.intent(before, null);
                }
                case Primitive.Link l -> {
                    link(l.element(), l.mode(), l.target());
                    yield l.intent(before, null);
                }
                case Primitive.Retarget r -> {
                    retarget(r.reference(), r.target());
                    yield r.intent(before, null);
                }
                case Primitive.Extract e -> extract(e, before);
                case Primitive.Inline i -> {
                    inline(i.reference());
                    yield i.intent(before, null);
                }
            };
            program = null;
            return change;
        } catch (RuntimeException failed) {
            restore(saved);
            throw failed;
        }
    }

    @Override
    public void edit(String path, String text) {
        throw new PrimitiveRejected("projection.no-sources", "a memory projection has no sources to edit");
    }

    @Override
    public Snapshot snapshot() {
        return new Saved(ids.save(), List.copyOf(order), Map.copyOf(nodes), List.copyOf(uses));
    }

    @Override
    public void restore(Snapshot snapshot) {
        var saved = (Saved) snapshot;
        ids.restore(saved.ids());
        order.clear();
        order.addAll(saved.order());
        nodes.clear();
        nodes.putAll(saved.nodes());
        uses.clear();
        uses.addAll(saved.uses());
        program = null;
    }

    @Override
    public LanguageRules rules() {
        return rules;
    }

    @Override
    public List<Diagnostic> diagnostics() {
        return List.of();
    }

    @Override
    public Optional<CompletionContext> completionContext(String path, String text, int offset) {
        return Optional.empty();
    }

    // ---- the primitives, on the graph

    private ElementId create(Kind kind, ElementId parent, String name, String argument) {
        requireName(name, kind == Kind.PACKAGE);
        if (kind == Kind.PARAM && argument == null && uses.stream().anyMatch(u -> u.target().equals(parent) && u.mode() == Mode.CALL)) {
            throw new PrimitiveRejected("create.argument", "the calls of " + render(parent) + " need an argument for " + name);
        }
        Address address = switch (kind) {
            case PACKAGE -> new Address.Package(name);
            case CLASS, INTERFACE, ENUM -> new Address.Type(ids.render(ids.address(parent)) + "." + name);
            case FIELD -> new Address.Field(parent, name);
            case METHOD -> new Address.Callable(parent, name, List.of());
            case PARAM -> new Address.Param(parent, name);
            case CONSTRUCTOR -> throw new PrimitiveRejected("projection.unsupported", "constructors are not created");
        };
        if (ids.has(address)) throw duplicate(ids.render(address));
        var id = ids.of(address, kind);
        nodes.put(id, new Node(kind, name, kind == Kind.PACKAGE ? null : parent, initialFacts(kind)));
        order.add(id);
        if (kind == Kind.PARAM) resign(parent);
        return id;
    }

    /** What a language writes by default for a new element. */
    private static Map<Fact, String> initialFacts(Kind kind) {
        return switch (kind) {
            case PACKAGE -> Map.of();
            case PARAM -> Map.of(Fact.TYPE, "Object");
            case CLASS, INTERFACE, ENUM -> modifiers("public", "false");
            case FIELD -> typed(modifiers("package", "false"), "Object");
            case METHOD, CONSTRUCTOR -> typed(modifiers("package", "false"), "void");
        };
    }

    private static Map<Fact, String> modifiers(String visibility, String isStatic) {
        return Map.of(Fact.VISIBILITY, visibility, Fact.STATIC, isStatic, Fact.ABSTRACT, "false", Fact.FINAL, "false");
    }

    private static Map<Fact, String> typed(Map<Fact, String> facts, String type) {
        var more = new EnumMap<Fact, String>(Fact.class);
        more.putAll(facts);
        more.put(Fact.TYPE, type);
        return Map.copyOf(more);
    }

    private void delete(ElementId element) {
        var doomed = subtree(element);
        var users = new LinkedHashSet<String>();
        for (var use : uses) if (doomed.contains(use.target()) && !doomed.contains(use.site())) users.add(render(use.site()));
        if (!users.isEmpty()) throw new PrimitiveRejected("delete.referenced", render(element) + " is used by " + users);
        var node = nodes.get(element);
        uses.removeIf(use -> doomed.contains(use.site()));
        order.removeAll(doomed);
        doomed.forEach(nodes::remove);
        ids.forget(element);
        if (node.kind() == Kind.PARAM) resign(node.parent());
    }

    /** The element and everything inside it. */
    private Set<ElementId> subtree(ElementId root) {
        var all = new LinkedHashSet<ElementId>(List.of(root));
        for (var grew = true; grew; ) {
            grew = false;
            for (var id : order) {
                if (!all.contains(id) && all.contains(nodes.get(id).parent())) grew = all.add(id);
            }
        }
        return all;
    }

    private void move(ElementId element, ElementId parent, ElementId before) {
        var node = nodes.get(element);
        if (node.kind().isType()) {
            var target = nodes.get(parent);
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
        nodes.put(element, node.under(parent));
        order.remove(element);
        order.add(before == null ? order.size() : order.indexOf(before), element);
        if (node.kind() == Kind.PARAM) resign(parent);
    }

    private void rename(ElementId element, String name) {
        requireName(name, false);
        var node = nodes.get(element);
        switch (node.kind()) {
            case PACKAGE -> throw new PrimitiveRejected("projection.unsupported", "a package is renamed by moving its types");
            case CONSTRUCTOR -> throw new PrimitiveRejected("projection.unsupported", "a constructor is named after its type");
            case CLASS, INTERFACE, ENUM -> {
                var old = ((Address.Type) ids.address(element)).qualified();
                var now = old.substring(0, old.lastIndexOf('.') + 1) + name;
                if (ids.has(new Address.Type(now))) throw duplicate(now);
                ids.rebindType(old, now);
                renameMentionsOf(element, node.name(), name);
            }
            default -> {
                var renamed = ids.address(element).withName(name);
                if (ids.has(renamed)) throw duplicate(ids.render(renamed));
                ids.rename(element, name);
            }
        }
        nodes.put(element, nodes.get(element).named(name));
        uses.replaceAll(use -> use.target().equals(element) ? new Use(use.site(), use.target(), name, use.mode()) : use);
    }

    /** A renamed type: its constructors are named after it, and the types written by its users name it anew. */
    private void renameMentionsOf(ElementId type, String old, String name) {
        var word = Pattern.compile("\\b" + Pattern.quote(old) + "\\b");
        var users = new LinkedHashSet<ElementId>();
        for (var use : uses) if (use.target().equals(type)) users.add(use.site());
        for (var id : order) {
            var node = nodes.get(id);
            if (node.kind() == Kind.CONSTRUCTOR && type.equals(node.parent())) {
                nodes.put(id, node.named(name));
                uses.replaceAll(use -> use.target().equals(id) ? new Use(use.site(), id, name, use.mode()) : use);
            }
            if (!users.contains(id)) continue;
            for (var fact : List.of(Fact.TYPE, Fact.SUPERCLASS)) {
                var text = nodes.get(id).facts().get(fact);
                if (text != null) nodes.put(id, nodes.get(id).with(fact, word.matcher(text).replaceAll(Matcher.quoteReplacement(name))));
            }
        }
    }

    private void set(ElementId element, String attribute, String value) {
        var fact = switch (attribute) {
            case "visibility", "static", "abstract", "final", "type", "superclass", "body" -> Fact.of(attribute);
            default -> throw new PrimitiveRejected("projection.unsupported", "a memory projection has no " + attribute);
        };
        var node = nodes.get(element);
        nodes.put(element, node.with(fact, value));
        if (fact == Fact.TYPE) relink(element, Mode.DECLARED, typeNamed(value)); // the text decides what it links to
        if (fact == Fact.SUPERCLASS) relink(element, Mode.EXTENDS, typeNamed(value));
        if (fact == Fact.TYPE && node.kind() == Kind.PARAM) resign(node.parent());
    }

    private void link(ElementId element, Mode mode, ElementId target) {
        relink(element, mode, target);
        var node = nodes.get(element);
        var name = nodes.get(target).name();
        if (mode == Mode.EXTENDS) nodes.put(element, node.with(Fact.SUPERCLASS, name));
        if (mode == Mode.DECLARED) {
            nodes.put(element, node.with(Fact.TYPE, name));
            if (node.kind() == Kind.PARAM) resign(node.parent());
        }
    }

    /** The element's link of {@code mode} targets {@code target} now (none if null); implements adds one more. */
    private void relink(ElementId element, Mode mode, ElementId target) {
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

    private void retarget(String reference, ElementId target) {
        var index = indexOf(reference);
        var use = uses.get(index);
        var from = nodes.get(use.target()).kind();
        var to = nodes.get(target).kind();
        Mode mode;
        if (from == to || from.isType() && to.isType()) mode = use.mode();
        else if (to == Kind.METHOD && (use.mode() == Mode.READ || use.mode() == Mode.WRITE || use.mode() == Mode.NEW)) mode = Mode.CALL;
        else throw new PrimitiveRejected("retarget." + use.mode().label(), "cannot express a " + use.mode().label()
                    + " of " + render(use.target()) + " with " + render(target));
        uses.set(index, new Use(use.site(), target, nodes.get(target).name(), mode));
    }

    private Change extract(Primitive.Extract extract, Program before) {
        var range = extract.fragment().split("-");
        var positions = new ArrayList<Integer>();
        for (var i = 0; i < uses.size(); i++) if (uses.get(i).site().equals(extract.method())) positions.add(i);
        int first;
        int last;
        try {
            first = Integer.parseInt(range[0].strip());
            last = Integer.parseInt(range[range.length - 1].strip());
        } catch (NumberFormatException notARange) {
            throw new PrimitiveRejected("extract.fragment", "'" + extract.fragment() + "' is not a range of uses");
        }
        if (first < 0 || last < first || last >= positions.size()) {
            throw new PrimitiveRejected("extract.fragment", "uses " + extract.fragment() + " are not uses of " + render(extract.method()));
        }
        requireName(extract.name(), false);
        var method = nodes.get(extract.method());
        var address = new Address.Callable(method.parent(), extract.name(), List.of());
        if (ids.has(address)) throw duplicate(ids.render(address));
        var created = ids.of(address, Kind.METHOD);
        nodes.put(created, new Node(Kind.METHOD, extract.name(), method.parent(),
                typed(modifiers("private", method.facts().getOrDefault(Fact.STATIC, "false")), "void")));
        order.add(created);
        var change = extract.intent(before, created);
        for (var k = first; k <= last; k++) {
            var use = uses.get(positions.get(k));
            uses.set(positions.get(k), new Use(created, use.target(), use.name(), use.mode()));
            change = change.expect(new Change.Resited(use.target(), extract.method(), created));
        }
        uses.add(positions.get(first), new Use(extract.method(), created, extract.name(), Mode.CALL));
        return change;
    }

    private void inline(String reference) {
        var index = indexOf(reference);
        var call = uses.get(index);
        if (call.mode() != Mode.CALL || nodes.get(call.target()).kind() != Kind.METHOD) {
            throw new PrimitiveRejected("inline.not-a-call", reference + " is not a call");
        }
        var body = uses.stream().filter(use -> use.site().equals(call.target()) && !use.mode().isLink()).toList();
        if (body.stream().anyMatch(use -> use.target().equals(call.target()))) {
            throw new PrimitiveRejected("inline.recursive", render(call.target()) + " calls itself");
        }
        uses.remove(index);
        uses.addAll(index, body.stream().map(use -> new Use(call.site(), use.target(), use.name(), use.mode())).toList());
    }

    // ---- helpers

    /** A callable's address follows the types of its parameters, in order. */
    private void resign(ElementId callable) {
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

    /** A parameter type key: the id of the workspace type the text names, or the text. */
    private String key(String type) {
        var named = typeNamed(type);
        return named == null ? type : named.value();
    }

    /** The workspace type a text names, by qualified or (unique) simple name; null if none. */
    private ElementId typeNamed(String text) {
        var qualified = ids.types().get(text);
        if (qualified != null) return qualified;
        var simple = ids.types().entrySet().stream().filter(e -> Address.simpleName(e.getKey()).equals(text))
                .map(Map.Entry::getValue).toList();
        return simple.size() == 1 ? simple.getFirst() : null;
    }

    /** The position in {@link #uses} of the reference with this id ({@code site@ordinal}). */
    private int indexOf(String reference) {
        var at = reference.lastIndexOf('@');
        var site = new ElementId(reference.substring(0, at));
        var ordinal = Integer.parseInt(reference.substring(at + 1));
        var seen = 0;
        for (var i = 0; i < uses.size(); i++) {
            if (uses.get(i).site().equals(site) && seen++ == ordinal) return i;
        }
        throw new IllegalArgumentException("no reference " + reference);
    }

    private String render(ElementId id) {
        return ids.render(ids.address(id));
    }

    private static void requireName(String name, boolean dotted) {
        if (name == null || name.isBlank() || name.chars().anyMatch(Character::isWhitespace) || !dotted && name.contains(".")) {
            throw new PrimitiveRejected("projection.invalid-identifier", "'" + name + "' is not a name");
        }
    }

    private static PrimitiveRejected duplicate(String address) {
        return new PrimitiveRejected("projection.duplicate", address + " already exists");
    }

    private void addUse(ElementId site, ElementId target, Mode mode) {
        uses.add(new Use(site, target, nodes.get(target).name(), mode));
        program = null;
    }

    /**
     * Builds a program by hand, by address: {@code type("a.A").field("a.A", "x", "int").method("a.A", "m", "int",
     * "int p").use("a.A#m(int)", "a.A#x", Mode.READ)}. A type written by qualified name that is in the workspace is
     * linked, any other is text. Everything but the uses is made with the projection's own primitives.
     */
    public static final class Builder {

        private final MemoryProjection projection;

        private Builder(MemoryProjection projection) {
            this.projection = projection;
        }

        /** The rules the projection answers with (default {@link #OPEN}). */
        public Builder rules(LanguageRules rules) {
            projection.rules = rules;
            return this;
        }

        public Builder type(String qualified) {
            return type(Kind.CLASS, qualified);
        }

        /** A class, interface or enum; its package (or outer type) is created on the way if needed. */
        public Builder type(Kind kind, String qualified) {
            var dot = qualified.lastIndexOf('.');
            var outer = qualified.substring(0, dot);
            var parent = projection.program().find(outer).map(Element::id)
                    .orElseGet(() -> projection.apply(new Primitive.Create(Kind.PACKAGE, null, outer)).produced());
            projection.apply(new Primitive.Create(kind, parent, qualified.substring(dot + 1)));
            return this;
        }

        public Builder field(String owner, String name, String type) {
            declare(projection.apply(new Primitive.Create(Kind.FIELD, id(owner), name)).produced(), type);
            return this;
        }

        /** A method returning {@code type}, with parameters written {@code "type name"}. */
        public Builder method(String owner, String name, String type, String... params) {
            var method = projection.apply(new Primitive.Create(Kind.METHOD, id(owner), name)).produced();
            declare(method, type);
            for (var param : params) {
                var space = param.lastIndexOf(' ');
                var created = projection.apply(new Primitive.Create(Kind.PARAM, method, param.substring(space + 1))).produced();
                declare(created, param.substring(0, space));
            }
            return this;
        }

        public Builder extend(String type, String superclass) {
            projection.apply(new Primitive.Link(id(type), Mode.EXTENDS, id(superclass)));
            return this;
        }

        public Builder implement(String type, String implemented) {
            projection.apply(new Primitive.Link(id(type), Mode.IMPLEMENTS, id(implemented)));
            return this;
        }

        public Builder fact(String element, Fact fact, String value) {
            projection.apply(new Primitive.Set(id(element), fact.label(), value));
            return this;
        }

        /** A use made from inside {@code site}: what a body would say. */
        public Builder use(String site, String target, Mode mode) {
            projection.addUse(id(site), id(target), mode);
            return this;
        }

        public MemoryProjection build() {
            return projection;
        }

        private void declare(ElementId element, String type) {
            var workspace = projection.program().find(type).filter(e -> e.kind().isType());
            if (workspace.isPresent()) projection.apply(new Primitive.Link(element, Mode.DECLARED, workspace.get().id()));
            else projection.apply(new Primitive.Set(element, "type", type));
        }

        private ElementId id(String address) {
            return projection.program().find(address).map(Element::id)
                    .orElseThrow(() -> new IllegalArgumentException("no element " + address));
        }
    }
}
