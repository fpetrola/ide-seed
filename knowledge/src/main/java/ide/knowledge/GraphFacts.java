package ide.knowledge;

import ide.program.Address;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Program;
import ide.program.Reference;
import ide.projection.Projection;
import ide.refactoring.Value;
import ide.rules.Facts;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The program as the relations rules read, asked by pattern: a view over {@link Program}, never a copy. Elements and
 * uses are named by id; kinds in lower case. Plus {@code known(name, value)}: what came with the problem being solved
 * (its path, its symbol...). Nothing here is of a language.
 * <pre>
 * kind(e, k)        name(e, n)        parent(e, p)      visibility(e, v)    top-level(t)        static(e)
 * top(e, t)         package(e, p)     enclosing(e, t)   subclass(a, b)      (a is b, or extends it)
 * overrides(m, n)   alike(a, b)       before(a, b)      constructor(k, t)   named(t, n)        (types)
 * unresolved(u, path, name, kind)     (kind: type, value, call or new)
 * site(u, s)        target(u, t)      known(name, value)
 * self-use(s, m)    type-use(s, m)    (a use from s of m, made on the object itself, or through a type)
 * writes(s, f)      new-use(s, k)     final(e)          abstract(e)         type(e, t)        (as written)
 * initialized(f)    (a field given its value where it is declared)
 * unimplemented(t, m)                 (an abstract method t inherits and nothing it has or inherits implements)
 * argument(i, v)    (what the problem names, by place: 0, 1...)
 * library(e)        scope(u, t)       expected(u, type)  start-value(type, v)   (what a use binds nothing in, what its
 *                                     place expects, and how the language writes what a type starts with)
 * member(e, owner, name, kind)        parameter(p, callable, name, passed)     constructor-for(k, type, use)
 * takes-arguments(m, use)             (m's parameters are of the types of what the use passes)
 * similar(e, type, name, kind)        (a member type has, declared or inherited, named one or two letters away from
 *                                     name, as a typo is; the nearest first)
 * exists(e)         declared(e, path, name)   (an element of the workspace; a member the file at path declares)
 * stores(p, k, f)   (a statement k makes to give f the value of its parameter p: only made, never read)
 * passes(p, s, f)   (s calling p's constructor first with what it gave f, and giving it no more: only made)
 * assigns(s, f)     (a statement of s gives f a value it says in no language)
 * superclass(t, u)  (u is the class t extends, of the workspace)     same-parameters(n, m)   (in order, of the same types)
 * uses(s, m)        (any reference from s to m: a use, a call, a new, a link)
 * </pre>
 * A state supposed ({@link #with}) is the graph as it would be: what is derived from where an element is (its type,
 * its top-level type, its package, its file, the elements before it) follows where it is supposed to be. An element
 * that goes into another goes last in it.
 */
final class GraphFacts implements Facts {

    private final Projection projection;
    private final Program program;
    private final Map<String, Value> known;
    /** What is supposed: tuples added and removed, by relation. */
    private final Map<String, java.util.Set<List<String>>> added;
    private final Map<String, java.util.Set<List<String>>> removed;
    /** Where an element is supposed to be, what one created is, and what a class extends, when a change says so. */
    private final Map<String, String> parents = new java.util.HashMap<>();
    private final Map<String, String> superclasses = new java.util.HashMap<>();
    private final Map<String, String> kinds = new java.util.HashMap<>();
    /** The elements supposed to go into a parent, in the order they went. */
    private final Map<String, List<String>> arrived = new java.util.HashMap<>();

    GraphFacts(Projection projection, Map<String, Value> known) {
        this(projection, known, Map.of(), Map.of());
    }

    private GraphFacts(Projection projection, Map<String, Value> known, Map<String, java.util.Set<List<String>>> added,
                       Map<String, java.util.Set<List<String>>> removed) {
        this.projection = projection;
        this.program = projection.program();
        this.known = known;
        this.added = added;
        this.removed = removed;
        for (var tuple : added.getOrDefault("parent", java.util.Set.of())) {
            parents.put(tuple.get(0), tuple.get(1));
            arrived.computeIfAbsent(tuple.get(1), p -> new java.util.ArrayList<>()).add(tuple.get(0));
        }
        for (var tuple : added.getOrDefault("kind", java.util.Set.of())) kinds.put(tuple.get(0), tuple.get(1));
        for (var tuple : added.getOrDefault("superclass", java.util.Set.of())) superclasses.put(tuple.get(0), tuple.get(1));
    }

    /** These facts with {@code more} and without {@code less}, after what they already suppose. */
    @Override
    public Facts with(java.util.Collection<ide.rules.Tuple> more, java.util.Collection<ide.rules.Tuple> less) {
        var nowAdded = copy(added);
        var nowRemoved = copy(removed);
        for (var tuple : less) {
            nowAdded.getOrDefault(tuple.relation(), new java.util.LinkedHashSet<>()).remove(tuple.values());
            nowRemoved.computeIfAbsent(tuple.relation(), r -> new java.util.LinkedHashSet<>()).add(tuple.values());
        }
        for (var tuple : more) {
            nowRemoved.getOrDefault(tuple.relation(), new java.util.LinkedHashSet<>()).remove(tuple.values());
            nowAdded.computeIfAbsent(tuple.relation(), r -> new java.util.LinkedHashSet<>()).add(tuple.values());
        }
        return new GraphFacts(projection, known, nowAdded, nowRemoved);
    }

    private static Map<String, java.util.Set<List<String>>> copy(Map<String, java.util.Set<List<String>>> tuples) {
        var copy = new java.util.LinkedHashMap<String, java.util.Set<List<String>>>();
        tuples.forEach((relation, values) -> copy.put(relation, new java.util.LinkedHashSet<>(values)));
        return copy;
    }

    @Override
    public Stream<List<String>> match(String relation, List<String> pattern) {
        var tuples = derived(relation, pattern);
        var gone = removed.get(relation);
        if (gone != null && !gone.isEmpty()) tuples = tuples.filter(tuple -> !gone.contains(tuple));
        var more = added.get(relation);
        if (more != null && !more.isEmpty()) tuples = Stream.concat(tuples, more.stream()).distinct();
        return tuples.filter(tuple -> Facts.matches(tuple, pattern));
    }

    private Stream<List<String>> derived(String relation, List<String> pattern) {
        var first = pattern.isEmpty() ? null : pattern.getFirst();
        Stream<List<String>> tuples = switch (relation) {
            case "kind" -> elements(first).map(e -> List.of(id(e), e.kind().name().toLowerCase(Locale.ROOT)));
            case "name" -> elements(first).map(e -> List.of(id(e), e.name()));
            case "parent" -> first == null && second(pattern) != null // what is in a parent: its children, not every element
                    ? program.contains(new ElementId(second(pattern))) ? program.children(new ElementId(second(pattern))).stream()
                            .map(e -> List.of(id(e), e.parent().value())) : Stream.empty()
                    : elements(first).filter(e -> e.parent() != null).map(e -> List.of(id(e), e.parent().value()));
            case "uses" -> references(first).map(r -> List.of(r.site().value(), r.target().value()));
            case "visibility" -> elements(first).filter(e -> e.fact(Fact.VISIBILITY) != null)
                    .map(e -> List.of(id(e), e.fact(Fact.VISIBILITY)));
            case "top-level" -> elements(first).filter(e -> topLevel(id(e))).map(e -> List.of(id(e)));
            case "static" -> elements(first).filter(e -> "true".equals(e.fact(Fact.STATIC))).map(e -> List.of(id(e)));
            case "final" -> elements(first).filter(e -> "true".equals(e.fact(Fact.FINAL))).map(e -> List.of(id(e)));
            case "abstract" -> elements(first).filter(e -> "true".equals(e.fact(Fact.ABSTRACT))).map(e -> List.of(id(e)));
            case "initialized" -> elements(first).filter(e -> "true".equals(e.fact(Fact.INITIALIZED))).map(e -> List.of(id(e)));
            case "type" -> elements(first).filter(e -> e.fact(Fact.TYPE) != null).map(e -> List.of(id(e), e.fact(Fact.TYPE)));
            case "writes" -> references(first).filter(r -> r.mode() == Mode.WRITE || r.mode() == Mode.UPDATE)
                    .map(r -> List.of(r.site().value(), r.target().value()));
            case "new-use" -> references(first).filter(r -> r.mode() == Mode.NEW).map(r -> List.of(r.site().value(), r.target().value()));
            case "unimplemented" -> types(first).flatMap(t -> program.unimplemented(t).stream().map(m -> List.of(id(t), id(m))));
            case "argument" -> {
                var arguments = Value.items(known.get("arguments"));
                yield java.util.stream.IntStream.range(0, arguments.size()).mapToObj(i -> List.of(String.valueOf(i), Value.asText(arguments.get(i))));
            }
            case "library" -> elements(first).filter(Element::inLibrary).map(e -> List.of(id(e)));
            case "scope" -> program.unresolved().stream().filter(u -> (first == null || first.equals(u.id())) && u.scope() != null)
                    .map(u -> List.of(u.id(), u.scope().value()));
            case "expected" -> holes(first).flatMap(u -> projection.needs(u.id()).stream()
                    .filter(n -> n.expected() != null).map(n -> List.of(u.id(), n.expected())));
            case "start-value" -> first == null ? Stream.empty() : Stream.of(List.of(first, projection.rules().defaultValue(first)));
            case "member" -> in(first, second(pattern)).filter(e -> parentOf(id(e)) != null)
                    .map(e -> List.of(id(e), parentOf(id(e)), e.name(), e.kind().name().toLowerCase(Locale.ROOT)));
            case "parameter" -> in(first, second(pattern)).filter(e -> e.kind() == Kind.PARAM).map(p -> List.of(id(p), parentOf(id(p)), p.name(),
                    pattern.size() > 3 && pattern.get(3) != null ? pattern.get(3) : ""));
            case "constructor-for" -> holes(pattern.size() > 2 ? pattern.get(2) : null).flatMap(u -> program.elements().stream()
                    .filter(k -> k.kind() == Kind.CONSTRUCTOR && takes(k, u)).map(k -> List.of(id(k), k.parent().value(), u.id())));
            case "takes-arguments" -> elements(first).filter(m -> m.kind() == Kind.METHOD || m.kind() == Kind.CONSTRUCTOR)
                    .flatMap(m -> holes(pattern.size() > 1 ? pattern.get(1) : null).filter(u -> takes(m, u)).map(u -> List.of(id(m), u.id())));
            case "self-use" -> uses(first, Reference.Receiver.SELF);
            case "type-use" -> uses(first, Reference.Receiver.TYPE);
            case "top" -> inTypes(first).map(e -> List.of(id(e), topOf(id(e))));
            case "package" -> inTypes(first).map(e -> List.of(id(e), packageOf(topOf(id(e)))));
            case "enclosing" -> inTypes(first).map(e -> List.of(id(e), enclosingOf(id(e))));
            case "subclass" -> (first == null ? program.types().stream().map(GraphFacts::id) : Stream.of(first)).filter(this::isType)
                    .flatMap(t -> chainOf(t).stream().map(s -> List.of(t, s)));
            case "overrides" -> elements(first).filter(e -> e.kind() == Kind.METHOD)
                    .flatMap(m -> program.overridden(m).stream().map(n -> List.of(id(m), id(n))));
            case "alike" -> elements(first).flatMap(a -> (second(pattern) != null ? elements(second(pattern)) : program.elements().stream())
                    .filter(b -> alike(a, b)).map(b -> List.of(id(a), id(b))));
            case "before" -> elements(first).filter(e -> parentOf(id(e)) != null).flatMap(a -> {
                var siblings = childrenOf(parentOf(id(a)));
                return siblings.subList(siblings.indexOf(id(a)) + 1, siblings.size()).stream().map(b -> List.of(id(a), b));
            });
            case "constructor" -> in(first, second(pattern)).filter(e -> e.kind() == Kind.CONSTRUCTOR)
                    .map(k -> List.of(id(k), parentOf(id(k))));
            case "named" -> program.types().stream().map(t -> List.of(id(t), t.name()));
            case "unresolved" -> program.unresolved().stream().filter(u -> first == null || first.equals(u.id()))
                    .filter(u -> program.contains(u.site()) && program.topLevel(u.site()).fact(Fact.FILE) != null)
                    .map(u -> List.of(u.id(), program.topLevel(u.site()).fact(Fact.FILE), u.name(), used(u.mode())));
            case "site" -> Stream.concat(
                    program.unresolved().stream().map(u -> List.of(u.id(), u.site().value())),
                    program.references().stream().map(r -> List.of(r.id(), r.site().value())));
            case "target" -> program.references().stream().map(r -> List.of(r.id(), r.target().value()));
            case "similar" -> pattern.size() < 3 || pattern.get(1) == null || pattern.get(2) == null ? Stream.empty()
                    : similar(new ElementId(pattern.get(1)), pattern.get(2)).map(m -> List.of(id(m), pattern.get(1), pattern.get(2),
                    m.kind().name().toLowerCase(Locale.ROOT)));
            case "exists" -> elements(first).filter(e -> !e.inLibrary()).map(e -> List.of(id(e)));
            case "stores", "passes", "same-superclass" -> Stream.empty();
            case "superclass" -> types(first).flatMap(t -> program.superclass(t.id()).stream().map(u -> List.of(id(t), id(u))));
            case "same-parameters" -> elements(first).filter(n -> n.kind() == Kind.METHOD || n.kind() == Kind.CONSTRUCTOR)
                    .flatMap(n -> elements(pattern.size() > 1 ? pattern.get(1) : null).filter(m -> m.kind() == Kind.METHOD)
                            .filter(m -> parameterTypes(n).equals(parameterTypes(m))).map(m -> List.of(id(n), id(m))));
            case "assigns" -> first == null ? Stream.empty() : elements(first).flatMap(s -> program.children(s.id()).stream())
                    .filter(e -> e.kind() == Kind.STATEMENT).flatMap(st -> assigned(st).stream()).map(f -> List.of(first, f));
            case "declared" -> elements(first).filter(e -> e.kind() == Kind.FIELD || e.kind() == Kind.METHOD)
                    .filter(e -> Objects.equals(fileOf(id(e)), pattern.size() > 1 ? pattern.get(1) : null)
                            || pattern.size() < 2 || pattern.get(1) == null)
                    .map(e -> List.of(id(e), String.valueOf(fileOf(id(e))), e.name()));
            case "known" -> known.entrySet().stream().filter(e -> e.getValue() != null && !(e.getValue() instanceof Value.Items))
                    .map(e -> List.of(e.getKey(), Value.asText(e.getValue())));
            default -> Stream.empty();
        };
        return tuples;
    }

    /** Where an element is: where it is supposed to be, if it is supposed to have moved; else where it is. */
    private String parentOf(String id) {
        var supposed = parents.get(id);
        if (supposed != null) return supposed;
        var element = new ElementId(id);
        if (!program.contains(element) || program.element(element).parent() == null) return null;
        var parent = program.element(element).parent().value();
        var gone = removed.get("parent");
        return gone != null && gone.contains(List.of(id, parent)) ? null : parent;
    }

    /** What an element is, in lower case: one created is what it is supposed to be. */
    private String kindOf(String id) {
        var supposed = kinds.get(id);
        if (supposed != null) return supposed;
        var element = new ElementId(id);
        return program.contains(element) ? program.element(element).kind().name().toLowerCase(Locale.ROOT) : null;
    }

    private boolean isType(String id) {
        var kind = kindOf(id);
        return kind != null && Kind.valueOf(kind.toUpperCase(Locale.ROOT)).isType();
    }

    /** What is in a parent, in order: what was there and stays, then what is supposed to go into it, last. */
    private List<String> childrenOf(String parent) {
        var children = new java.util.ArrayList<String>();
        if (program.contains(new ElementId(parent))) {
            for (var child : program.children(new ElementId(parent))) if (parent.equals(parentOf(id(child)))) children.add(id(child));
        }
        for (var child : arrived.getOrDefault(parent, List.of())) if (!children.contains(child) && parent.equals(parentOf(child))) children.add(child);
        return children;
    }

    /** A type and the classes it extends, the nearest first: as they are supposed to be, if a change says so. */
    private List<String> chainOf(String type) {
        var chain = new java.util.ArrayList<String>();
        for (var t = type; t != null && !chain.contains(t); t = superclassOf(t)) chain.add(t);
        return chain;
    }

    /** The class of the workspace a class extends: the one it is supposed to, else the one it does. */
    private String superclassOf(String type) {
        var supposed = superclasses.get(type);
        if (supposed != null) return supposed;
        var element = new ElementId(type);
        if (!program.contains(element)) return null;
        var real = program.superclass(element).map(GraphFacts::id).orElse(null);
        var gone = removed.get("superclass");
        return real != null && gone != null && gone.contains(List.of(type, real)) ? null : real;
    }

    /** The type an element is in, or the element itself if it is a type. */
    private String enclosingOf(String id) {
        var at = id;
        while (at != null && !isType(at)) at = parentOf(at);
        return at;
    }

    /** The outermost type an element is in, or the element itself. */
    private String topOf(String id) {
        var at = id;
        for (var parent = parentOf(at); parent != null && !"package".equals(kindOf(parent)); parent = parentOf(at)) at = parent;
        return at;
    }

    /** The file a top-level type is written in, of the element's top-level type. */
    private String fileOf(String id) {
        var top = new ElementId(topOf(id));
        return program.contains(top) ? program.element(top).fact(Fact.FILE) : null;
    }

    /**
     * The members {@code type} has, declared or inherited from the workspace, whose names are almost {@code name}: one
     * or two letters away, as a typo is; the nearest first.
     */
    private Stream<Element> similar(ElementId type, String name) {
        if (!program.contains(type)) return Stream.empty();
        var members = new java.util.ArrayList<Element>();
        for (var t = java.util.Optional.of(program.element(type)); t.isPresent(); t = program.superclass(t.get().id())) {
            members.addAll(program.members(t.get().id()));
        }
        return members.stream().filter(m -> distance(m.name(), name) > 0 && distance(m.name(), name) <= 2)
                .sorted(java.util.Comparator.comparingInt(m -> distance(m.name(), name)));
    }

    /** How many letters must be inserted, deleted, replaced or swapped to turn one name into the other. */
    private static int distance(String a, String b) {
        var d = new int[a.length() + 1][b.length() + 1];
        for (var i = 0; i <= a.length(); i++) {
            for (var j = 0; j <= b.length(); j++) {
                if (i == 0 || j == 0) d[i][j] = i + j;
                else {
                    var replace = d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                    d[i][j] = Math.min(replace, Math.min(d[i - 1][j], d[i][j - 1]) + 1);
                    if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                        d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                    }
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** The types of a callable's parameters, in order. */
    private List<String> parameterTypes(Element callable) {
        return program.children(callable.id()).stream().filter(e -> e.kind() == Kind.PARAM).map(p -> String.valueOf(p.fact(Fact.TYPE))).toList();
    }

    /** The field a statement gives a value, when all it does can be said in no language. */
    private java.util.Optional<String> assigned(Element statement) {
        return projection.said(statement.id()).map(text -> ide.program.Body.parse(text, program, statement.parent()).statements())
                .filter(said -> said.size() == 1 && said.getFirst() instanceof ide.program.Body.Assign)
                .map(said -> ((ide.program.Body.Assign) said.getFirst()).field().value());
    }

    /** The uses that bind nothing: the one named, or all. */
    private Stream<ide.program.Unresolved> holes(String id) {
        return program.unresolved().stream().filter(u -> id == null || id.equals(u.id()));
    }

    /** Whether a callable's parameters are, in order, of the types of what a use that binds nothing passes. */
    private boolean takes(Element callable, ide.program.Unresolved use) {
        var arguments = projection.needs(use.id()).map(n -> n.arguments()).orElse(List.of());
        var params = program.children(callable.id()).stream().filter(e -> e.kind() == Kind.PARAM).toList();
        if (params.size() != arguments.size()) return false;
        for (var i = 0; i < params.size(); i++) {
            if (!arguments.get(i).type().equals(params.get(i).fact(Fact.TYPE))) return false;
        }
        return true;
    }

    /** The uses made from {@code site} (or from anywhere) on {@code receiver}: site and target. */
    private Stream<List<String>> uses(String site, Reference.Receiver receiver) {
        return references(site).filter(r -> r.receiver() == receiver && !r.mode().isLink())
                .map(r -> List.of(r.site().value(), r.target().value()));
    }

    /** The references made from {@code site}, or from anywhere. */
    private Stream<Reference> references(String site) {
        return site == null ? program.references().stream()
                : program.contains(new ElementId(site)) ? program.referencesFrom(new ElementId(site)).stream() : Stream.<Reference>empty();
    }

    private static String second(List<String> pattern) {
        return pattern.size() > 1 ? pattern.get(1) : null;
    }

    /**
     * The element named, if any is; else what is in {@code parent} as supposed, if it is named (what stays and what
     * arrives); else every element.
     */
    private Stream<Element> in(String id, String parent) {
        if (id != null || parent == null) return elements(id);
        return childrenOf(parent).stream().map(ElementId::new).filter(program::contains).map(program::element);
    }

    /** The element named, if any is; every element, if none is. */
    private Stream<Element> elements(String id) {
        if (id == null) return program.elements().stream();
        var element = new ElementId(id);
        return program.contains(element) ? Stream.of(program.element(element)) : Stream.empty();
    }

    /** As {@link #elements}, but only what lives in a type: a type itself, or anything inside one. */
    private Stream<Element> inTypes(String id) {
        return elements(id).filter(e -> e.kind() != Kind.PACKAGE && (e.kind().isType() || e.parent() != null));
    }

    private Stream<Element> types(String id) {
        return elements(id).filter(e -> e.kind().isType());
    }

    private boolean topLevel(String id) {
        if (!isType(id)) return false;
        var parent = parentOf(id);
        return parent == null || "package".equals(kindOf(parent));
    }

    private static String id(Element e) {
        return e.id().value();
    }

    /** The package a top-level type is in, by its qualified name. */
    private String packageOf(String topLevel) {
        var parent = parentOf(topLevel);
        if (parent != null && program.contains(new ElementId(parent))) return program.element(new ElementId(parent)).qualified();
        var element = new ElementId(topLevel);
        if (!program.contains(element)) return "";
        var qualified = program.element(element).qualified();
        var dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private static String used(Mode mode) {
        return mode == Mode.CALL ? "call" : mode == Mode.NEW ? "new" : mode.isTypeUse() ? "type" : "value";
    }

    /** Two members that would be the same one in one type: same kind and name, and for a callable, same parameter types. */
    private static boolean alike(Element a, Element b) {
        if (a.kind() != b.kind() || !a.name().equals(b.name()) || a.kind().isType() || a.kind() == Kind.PACKAGE) return false;
        return !(a.address() instanceof Address.Callable ca && b.address() instanceof Address.Callable cb)
                || Objects.equals(ca.keys(), cb.keys());
    }
}
