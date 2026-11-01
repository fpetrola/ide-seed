package ide.refactoring.script;

import ide.program.Address;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Mode;
import ide.program.Program;
import ide.program.Reference;
import ide.program.Unresolved;
import ide.projection.LanguageRules;
import ide.projection.Needs;
import ide.projection.Projection;
import ide.refactoring.Value;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The functions and filters scripts can use, over typed values. Where an element is expected, an element value is
 * one by id, and a text is an address (what a script writes by hand); a reference, or a use that binds nothing, is a
 * reference value.
 */
final class ScriptFunctions {

    private final Program graph;
    private final LanguageRules rules;

    private final Projection projection;

    ScriptFunctions(Program graph, LanguageRules rules, Projection projection) {
        this.graph = graph;
        this.rules = rules;
        this.projection = projection;
    }

    Value call(String name, List<Value> args) {
        return switch (name) {
            // elements
            case "name" -> text(element(args, 0).name());
            case "kind" -> text(element(args, 0).kind().name());
            case "qualified" -> text(element(args, 0).qualified());
            case "owner" -> elem(element(args, 0).parent());
            case "top" -> elem(graph.topLevel(element(args, 0).id()).id());
            case "attr" -> text(element(args, 0).fact(Fact.of(text(args, 1))));
            case "type" -> text(element(args, 0).fact(Fact.TYPE));
            case "declared-type" -> graph.declaredType(element(args, 0).id()).map(e -> elem(e.id())).orElse(null);
            case "superclass" -> graph.superclass(element(args, 0).id()).map(e -> elem(e.id())).orElse(null);
            case "enclosing" -> elem(graph.enclosingType(element(args, 0).id()).id());
            case "member-named" -> graph.members(element(args, 0).id()).stream().filter(m -> alike(m, element(args, 1)))
                    .map(m -> elem(m.id())).findFirst().orElse(null);
            case "field-in" -> graph.typesIn(text(args, 0)).stream()
                    .flatMap(t -> graph.members(t.id()).stream())
                    .filter(m -> m.kind().name().equals("FIELD") && m.name().equals(text(args, 1)))
                    .map(m -> elem(m.id())).findFirst().orElse(null);
            case "top-in" -> elem(graph.typesIn(text(args, 0)).getFirst().id());
            case "type-in" -> types(text(args, 0)).stream().filter(t -> t.name().equals(text(args, 1))).findFirst()
                    .map(t -> elem(t.id())).orElse(null);
            case "members-in" -> elems(types(text(args, 0)).stream().flatMap(t -> graph.members(t.id()).stream())
                    .filter(m -> m.name().equals(text(args, 1))).toList());
            case "methods-in" -> elems(types(text(args, 0)).stream().flatMap(t -> graph.members(t.id()).stream())
                    .filter(m -> m.kind().name().equals("METHOD") && m.name().equals(text(args, 1))).toList());
            case "inside" -> projection.inside(text(args, 0), Integer.parseInt(text(args, 1)), Integer.parseInt(text(args, 2)))
                    .map(ScriptFunctions::elem).orElse(null);
            case "element-at" -> projection.elementAt(text(args, 0), Integer.parseInt(text(args, 1)), Integer.parseInt(text(args, 2)))
                    .map(ScriptFunctions::elem).orElse(null);
            case "unimplemented" -> args.getFirst() == null ? elems(List.of()) : elems(unimplemented(element(args, 0)));
            // language
            case "default-value" -> text(rules.defaultValue(text(args, 0)));
            case "wider" -> { // than it is; or, given another element, at least as wide as that one too
                var all = rules.visibilities(graph, element(args, 0).id());
                var from = all.indexOf(element(args, 0).fact(Fact.VISIBILITY)) + 1;
                if (args.size() > 1) from = Math.max(from, all.indexOf(element(args, 1).fact(Fact.VISIBILITY)));
                yield texts(all.subList(Math.min(from, all.size()), all.size()));
            }
            case "overridden" -> graph.overridden(element(args, 0)).map(e -> elem(e.id())).orElse(null);
            case "unresolved-in" -> graph.unresolved().stream() // a use in the file at a path, binding nothing by that name
                    .filter(use -> use.name().equals(text(args, 1)) && text(args, 0).equals(graph.topLevel(use.site()).fact(Fact.FILE)))
                    .filter(use -> args.size() < 3 || used(use.mode()).equals(text(args, 2)))
                    .<Value>map(use -> new Value.Ref(use.id())).findFirst().orElse(null);
            case "scope" -> args.getFirst() == null ? null : elem(hole(args.getFirst()).scope());
            case "expected" -> text(needs(args.getFirst()).expected());
            case "arguments" -> new Value.Items(needs(args.getFirst()).arguments().stream() // each its name, then its type
                    .<Value>map(a -> texts(List.of(a.name(), a.type()))).toList());
            case "types-named" -> elems(graph.types().stream().filter(type -> type.name().equals(text(args, 0))).toList());
            case "similar" -> args.getFirst() == null ? elems(List.of()) : elems(similar(element(args, 0), text(args, 1), text(args, 2)));
            // queries
            case "members" -> elems(graph.members(element(args, 0).id()));
            case "fields" -> elems(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("FIELD")).toList());
            case "methods" -> elems(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("METHOD")).toList());
            case "params" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("PARAM")).toList());
            case "constructors" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("CONSTRUCTOR")).toList());
            case "body" -> text(element(args, 0).fact(Fact.BODY));
            case "said" -> text(ide.program.Body.name(element(args, 0).id())); // how a body names it
            case "value-given" -> said(element(args, 0).id()).flatMap(body -> body.statements().stream()
                    .filter(st -> st instanceof ide.program.Body.Assign given && given.field().equals(element(args, 1).id()))
                    .map(st -> ide.program.Body.text(((ide.program.Body.Assign) st).value())).findFirst())
                    .map(ScriptFunctions::text).orElse(null); // what a body gives a field, said in no language
            case "said-without" -> said(element(args, 0).id()).map(body -> new ide.program.Body(body.statements().stream()
                    .filter(st -> !(st instanceof ide.program.Body.Assign given && given.field().equals(element(args, 1).id())))
                    .toList()).text()).map(ScriptFunctions::text).orElse(null); // the rest of what it does
            case "types-of" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().isType()).toList());
            case "subclasses" -> elems(graph.subclasses(element(args, 0).id()));
            case "family" -> elems(graph.family(element(args, 0)));
            case "refs" -> new Value.Items(graph.referencesTo(element(args, 0).id()).stream().<Value>map(r -> new Value.Ref(r.id())).toList());
            // references
            case "mode" -> text(reference(args.getFirst()).mode().label());
            case "site" -> elem(graph.unresolved(ref(args.getFirst())).map(Unresolved::site).orElseGet(() -> reference(args.getFirst()).site()));
            case "target" -> elem(reference(args.getFirst()).target());
            // text and logic
            case "cap" -> text(cap(text(args, 0)));
            case "decap" -> text(text(args, 0).isEmpty() ? "" : Character.toLowerCase(text(args, 0).charAt(0)) + text(args, 0).substring(1));
            case "join" -> text(Value.items(args.get(0)).stream().map(Value::asText).collect(Collectors.joining(text(args, 1))));
            case "concat" -> text(args.stream().map(Value::asText).collect(Collectors.joining()));
            case "eq" -> bool(text(args, 0).equals(text(args, 1)));
            case "ne" -> bool(!text(args, 0).equals(text(args, 1)));
            case "not" -> bool(!Value.truthy(args.get(0)));
            case "and" -> bool(args.stream().allMatch(Value::truthy));
            case "or" -> bool(args.stream().anyMatch(Value::truthy));
            case "empty" -> bool(!Value.truthy(args.get(0)));
            case "nonempty" -> bool(Value.truthy(args.get(0)));
            case "count" -> text(String.valueOf(Value.items(args.get(0)).size())); // numbers are text; eq compares them so
            case "first" -> Value.items(args.get(0)).isEmpty() ? null : Value.items(args.get(0)).getFirst();
            case "last" -> Value.items(args.get(0)).isEmpty() ? null : Value.items(args.get(0)).getLast();
            default -> throw new IllegalArgumentException("unknown script function: " + name);
        };
    }

    boolean filter(String name, Value item, Value arg) {
        return switch (name) {
            case "within" -> within(item, arg);
            case "not-within" -> !within(item, arg);
            case "of-kind" -> element(item).kind().name().equals(Value.asText(arg));
            case "named" -> element(item).name().equals(Value.asText(arg));
            case "with-attr" -> {
                var spec = Value.asText(arg);
                var eq = spec.indexOf('=');
                yield spec.substring(eq + 1).equals(element(item).fact(Fact.of(spec.substring(0, eq))));
            }
            default -> throw new IllegalArgumentException("unknown script filter: " + name);
        };
    }

    /** Whether an element, or the site of a reference, is (inside) {@code container}. */
    private boolean within(Value item, Value container) {
        var start = item instanceof Value.Ref ? reference(item).site() : element(item).id();
        var target = element(container).id();
        for (var e = graph.element(start); ; e = graph.element(e.parent())) {
            if (e.id().equals(target)) return true;
            if (e.parent() == null) return false;
        }
    }

    private Element element(List<Value> args, int index) {
        return element(args.get(index));
    }

    private Element element(Value value) {
        return switch (value) {
            case Value.Elem e when graph.contains(e.id()) -> graph.element(e.id());
            case Value.Elem e -> throw new IllegalArgumentException("no element " + e.id().value());
            case Value.Text t -> graph.find(t.text()).orElseThrow(() -> new IllegalArgumentException("no element " + t.text()));
            case null, default -> throw new IllegalArgumentException("not an element: " + Value.asText(value));
        };
    }

    /** The types declared in a file, nested ones too. */
    private List<Element> types(String path) {
        var types = new java.util.ArrayList<Element>();
        var pending = new java.util.ArrayDeque<>(graph.typesIn(path));
        while (!pending.isEmpty()) {
            var type = pending.poll();
            types.add(type);
            graph.children(type.id()).stream().filter(e -> e.kind().isType()).forEach(pending::add);
        }
        return types;
    }

    /**
     * The methods a type's supertypes leave abstract and nothing in it or above it implements: once each, as the
     * nearest supertype declares it.
     */
    private List<Element> unimplemented(Element type) {
        var above = graph.ancestors(type);
        var concrete = new java.util.ArrayList<Element>(graph.members(type.id()));
        above.forEach(a -> concrete.addAll(graph.members(a.id())));
        concrete.removeIf(m -> "true".equals(m.fact(Fact.ABSTRACT)) || !m.kind().name().equals("METHOD"));
        var missing = new java.util.ArrayList<Element>();
        for (var ancestor : above) {
            for (var method : graph.members(ancestor.id())) {
                if (!method.kind().name().equals("METHOD") || !"true".equals(method.fact(Fact.ABSTRACT))) continue;
                if (concrete.stream().anyMatch(c -> sameSignature(c, method)) || missing.stream().anyMatch(m -> sameSignature(m, method))) continue;
                missing.add(method);
            }
        }
        return missing;
    }

    private static boolean sameSignature(Element a, Element b) {
        return a.name().equals(b.name()) && a.address() instanceof Address.Callable ca && b.address() instanceof Address.Callable cb
                && ca.keys().equals(cb.keys());
    }

    /** What a use that binds nothing is: a {@code type}, a {@code call}, a {@code new} object, or a {@code value} read or written. */
    private static String used(Mode mode) {
        return mode == Mode.CALL ? "call" : mode == Mode.NEW ? "new" : mode.isTypeUse() ? "type" : "value";
    }

    /**
     * The members of a kind that {@code type} has, declared or inherited from the workspace, whose names are almost
     * {@code name}: one or two letters away, as a typo is; the nearest first.
     */
    private List<Element> similar(Element type, String name, String kind) {
        var members = new java.util.ArrayList<Element>();
        for (var t = java.util.Optional.of(type); t.isPresent(); t = graph.superclass(t.get().id())) {
            graph.members(t.get().id()).stream().filter(m -> m.kind().name().equals(kind)).forEach(members::add);
        }
        return members.stream().filter(m -> distance(m.name(), name) > 0 && distance(m.name(), name) <= 2)
                .sorted(java.util.Comparator.comparingInt(m -> distance(m.name(), name))).toList();
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

    private String ref(Value value) {
        if (!(value instanceof Value.Ref ref)) throw new IllegalArgumentException("not a reference: " + Value.asText(value));
        return ref.id();
    }

    /** What a use that binds nothing needs, as the projection says. */
    private Needs needs(Value value) {
        return projection.needs(ref(value)).orElseThrow(() -> new IllegalArgumentException("nothing known of what " + Value.asText(value) + " needs"));
    }

    private Unresolved hole(Value value) {
        return graph.unresolved(ref(value)).orElseThrow(() -> new IllegalArgumentException("not a use that binds nothing: " + Value.asText(value)));
    }

    private Reference reference(Value value) {
        if (!(value instanceof Value.Ref ref)) throw new IllegalArgumentException("not a reference: " + Value.asText(value));
        return graph.reference(ref.id());
    }

    private static String text(List<Value> args, int index) {
        return Value.asText(args.get(index));
    }

    /** A body as the projection says it in no language, if it can. */
    private java.util.Optional<ide.program.Body> said(ide.program.ElementId callable) {
        return projection.said(callable).map(text -> ide.program.Body.parse(text, graph, callable));
    }

    private static Value text(String text) {
        return text == null ? null : new Value.Text(text);
    }

    private static Value bool(boolean value) {
        return new Value.Bool(value);
    }

    private static Value elem(ElementId id) {
        return id == null ? null : new Value.Elem(id);
    }

    private static Value elems(Collection<Element> elements) {
        return new Value.Items(elements.stream().<Value>map(e -> new Value.Elem(e.id())).toList());
    }

    private static Value texts(List<String> texts) {
        return new Value.Items(texts.stream().<Value>map(Value.Text::new).toList());
    }

    /** Of the same kind and name; for a method, with the same parameter types too. */
    private static boolean alike(Element a, Element b) {
        if (a.kind() != b.kind() || !a.name().equals(b.name())) return false;
        return !(a.address() instanceof Address.Callable ca && b.address() instanceof Address.Callable cb) || ca.keys().equals(cb.keys());
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
