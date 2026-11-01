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
 * </pre>
 */
final class GraphFacts implements Facts {

    private final Projection projection;
    private final Program program;
    private final Map<String, Value> known;

    GraphFacts(Projection projection, Map<String, Value> known) {
        this.projection = projection;
        this.program = projection.program();
        this.known = known;
    }

    @Override
    public Stream<List<String>> match(String relation, List<String> pattern) {
        var first = pattern.isEmpty() ? null : pattern.getFirst();
        Stream<List<String>> tuples = switch (relation) {
            case "kind" -> elements(first).map(e -> List.of(id(e), e.kind().name().toLowerCase(Locale.ROOT)));
            case "name" -> elements(first).map(e -> List.of(id(e), e.name()));
            case "parent" -> elements(first).filter(e -> e.parent() != null).map(e -> List.of(id(e), e.parent().value()));
            case "visibility" -> elements(first).filter(e -> e.fact(Fact.VISIBILITY) != null)
                    .map(e -> List.of(id(e), e.fact(Fact.VISIBILITY)));
            case "top-level" -> elements(first).filter(this::topLevel).map(e -> List.of(id(e)));
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
            case "member" -> elements(first).filter(e -> e.parent() != null)
                    .map(e -> List.of(id(e), e.parent().value(), e.name(), e.kind().name().toLowerCase(Locale.ROOT)));
            case "parameter" -> elements(first).filter(e -> e.kind() == Kind.PARAM).map(p -> List.of(id(p), p.parent().value(), p.name(),
                    pattern.size() > 3 && pattern.get(3) != null ? pattern.get(3) : ""));
            case "constructor-for" -> holes(pattern.size() > 2 ? pattern.get(2) : null).flatMap(u -> program.elements().stream()
                    .filter(k -> k.kind() == Kind.CONSTRUCTOR && takes(k, u)).map(k -> List.of(id(k), k.parent().value(), u.id())));
            case "takes-arguments" -> elements(first).filter(m -> m.kind() == Kind.METHOD || m.kind() == Kind.CONSTRUCTOR)
                    .flatMap(m -> holes(pattern.size() > 1 ? pattern.get(1) : null).filter(u -> takes(m, u)).map(u -> List.of(id(m), u.id())));
            case "self-use" -> uses(first, Reference.Receiver.SELF);
            case "type-use" -> uses(first, Reference.Receiver.TYPE);
            case "top" -> inTypes(first).map(e -> List.of(id(e), id(program.topLevel(e.id()))));
            case "package" -> inTypes(first).map(e -> List.of(id(e), packageOf(program.topLevel(e.id()))));
            case "enclosing" -> inTypes(first).map(e -> List.of(id(e), id(program.enclosingType(e.id()))));
            case "subclass" -> types(first).flatMap(t -> program.superclassChain(t.id()).stream().map(s -> List.of(id(t), id(s))));
            case "overrides" -> elements(first).filter(e -> e.kind() == Kind.METHOD)
                    .flatMap(m -> program.overridden(m).stream().map(n -> List.of(id(m), id(n))));
            case "alike" -> elements(first).flatMap(a -> program.elements().stream().filter(b -> alike(a, b))
                    .map(b -> List.of(id(a), id(b))));
            case "before" -> elements(first).filter(e -> e.parent() != null).flatMap(a -> {
                var siblings = program.children(a.parent());
                return siblings.subList(siblings.indexOf(a) + 1, siblings.size()).stream().map(b -> List.of(id(a), id(b)));
            });
            case "constructor" -> elements(first).filter(e -> e.kind() == Kind.CONSTRUCTOR)
                    .map(k -> List.of(id(k), k.parent().value()));
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
            case "stores", "passes" -> Stream.empty();
            case "assigns" -> first == null ? Stream.empty() : elements(first).flatMap(s -> program.children(s.id()).stream())
                    .filter(e -> e.kind() == Kind.STATEMENT).flatMap(st -> assigned(st).stream()).map(f -> List.of(first, f));
            case "declared" -> elements(first).filter(e -> e.kind() == Kind.FIELD || e.kind() == Kind.METHOD)
                    .filter(e -> Objects.equals(program.topLevel(e.id()).fact(Fact.FILE), pattern.size() > 1 ? pattern.get(1) : null)
                            || pattern.size() < 2 || pattern.get(1) == null)
                    .map(e -> List.of(id(e), String.valueOf(program.topLevel(e.id()).fact(Fact.FILE)), e.name()));
            case "known" -> known.entrySet().stream().filter(e -> e.getValue() != null && !(e.getValue() instanceof Value.Items))
                    .map(e -> List.of(e.getKey(), Value.asText(e.getValue())));
            default -> Stream.empty();
        };
        return tuples.filter(tuple -> Facts.matches(tuple, pattern));
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

    private boolean topLevel(Element e) {
        return e.kind().isType() && (e.parent() == null || program.element(e.parent()).kind() == Kind.PACKAGE);
    }

    private static String id(Element e) {
        return e.id().value();
    }

    private static String packageOf(Element topLevel) {
        var qualified = topLevel.qualified();
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
