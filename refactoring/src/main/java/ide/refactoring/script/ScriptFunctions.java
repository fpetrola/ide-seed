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
            case "find" -> find(args.getFirst()); // an element by id or address, none if there is none
            case "simple" -> text(text(args, 0).substring(text(args, 0).lastIndexOf('.') + 1)); // a name without its qualifier
            case "type-in" -> types(text(args, 0)).stream().filter(t -> t.name().equals(text(args, 1))).findFirst()
                    .map(t -> elem(t.id())).orElse(null);
            case "inside" -> projection.inside(text(args, 0), Integer.parseInt(text(args, 1)), Integer.parseInt(text(args, 2)))
                    .map(id -> elem(graph.memberOf(id).id())).orElse(null); // a place in a body is in its method
            case "element-at" -> projection.elementAt(text(args, 0), Integer.parseInt(text(args, 1)), Integer.parseInt(text(args, 2)))
                    .map(ScriptFunctions::elem).orElse(null);
            case "unimplemented" -> args.getFirst() == null ? elems(List.of()) : elems(graph.unimplemented(element(args, 0)));
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
            // queries
            case "members" -> elems(graph.members(element(args, 0).id()));
            case "fields" -> elems(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("FIELD")).toList());
            case "methods" -> elems(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("METHOD")).toList());
            case "params" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("PARAM")).toList());
            case "param-named" -> graph.children(element(args, 0).id()).stream() // a callable's parameter by its name
                    .filter(e -> e.kind().name().equals("PARAM") && e.name().equals(text(args, 1))).findFirst()
                    .map(e -> elem(e.id())).orElseThrow(() -> new IllegalArgumentException(element(args, 0).qualified() + " has no parameter " + text(args, 1)));
            case "free-name" -> text(freeName(element(args, 0), text(args, 1))); // a name no variable of a callable has: the name, else name2, ...
            case "valued" -> new Value.Items(assigned(args.get(0)).keySet().stream().<Value>map(Value.Text::new).toList()); // the names of name = value, ...
            case "value-of" -> text(assigned(args.get(0)).get(text(args, 1))); // the value a name is given in name = value, ...
            case "in-order" -> inOrder(element(args, 0), Value.items(args.get(1)).stream().map(this::element).toList(),
                    Value.items(args.get(2)).stream().map(Value::asText).toList(), Value.items(args.get(3)));
            case "variables" -> elems(Value.items(args.get(1)).stream().map(item -> graph.children(element(args, 0).id()).stream() // of a callable, named as the items are
                    .filter(e -> (e.kind().name().equals("PARAM") || e.kind().name().equals("LOCAL")) && e.name().equals(element(item).name()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("no variable " + element(item).name() + " in " + element(args, 0).qualified())))
                    .toList());
            case "constructors" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("CONSTRUCTOR")).toList());
            case "body" -> text(element(args, 0).fact(Fact.BODY));
            case "getter-of" -> getterOf(element(args, 0), Value.items(args.get(1)).stream().map(this::element).toList(), element(args, 2));
            case "after" -> { // the statements that follow one, in order, among those of what holds it
                var statement = element(args, 0);
                var all = graph.children(statement.parent()).stream().filter(e -> e.kind().name().equals("STATEMENT")).toList();
                yield elems(all.subList(all.indexOf(statement) + 1, all.size()));
            }
            case "ordered" -> projection.ordered(element(args, 0).id(), element(args, 1).id()).map(ScriptFunctions::text).orElse(null); // what keeps two statements in order, none if nothing
            case "passed" -> { // the statements of its block a statement passes going before another
                var statement = element(args, 0);
                var all = graph.children(statement.parent()).stream().filter(e -> e.kind().name().equals("STATEMENT")).toList();
                var from = all.indexOf(statement);
                var to = all.indexOf(element(args, 1));
                if (to < 0) throw new IllegalArgumentException(element(args, 1).qualified() + " is not of the block of " + statement.qualified());
                yield elems(from < to ? all.subList(from + 1, to) : all.subList(to, from));
            }
            case "statements" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("STATEMENT")).toList());
            case "types-of" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().isType()).toList());
            case "subclasses" -> elems(graph.subclasses(element(args, 0).id()));
            case "family" -> elems(graph.family(element(args, 0)));
            case "refs" -> refs(Value.items(args.get(0)).stream().map(this::element).toList());
            // references
            case "mode" -> text(reference(args.getFirst()).mode().label());
            case "site" -> elem(graph.unresolved(ref(args.getFirst())).map(Unresolved::site).orElseGet(() -> reference(args.getFirst()).site()));
            case "target" -> elem(reference(args.getFirst()).target());
            case "statement" -> elem(reference(args.getFirst()).statement()); // the statement of its body a use is in
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
            case "list" -> new Value.Items(args.stream().flatMap(a -> Value.items(a).stream()).toList()); // its arguments, lists flattened
            case "reversed" -> new Value.Items(Value.items(args.get(0)).reversed());
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

    private Value find(Value value) {
        if (value instanceof Value.Elem e) return graph.contains(e.id()) ? e : null;
        var text = Value.asText(value);
        if (graph.contains(new ElementId(text))) return elem(new ElementId(text));
        return graph.find(text).map(e -> elem(e.id())).orElse(null);
    }

    private String freeName(Element callable, String name) {
        var taken = graph.children(callable.id()).stream().filter(e -> e.kind().name().equals("PARAM") || e.kind().name().equals("LOCAL"))
                .map(Element::name).collect(Collectors.toSet());
        if (!taken.contains(name)) return name;
        var n = 2;
        while (taken.contains(name + n)) n++;
        return name + n;
    }

    /** {@code name = value, ...}, in the order written; the commas inside a value's brackets or quotes are its own. */
    private static java.util.Map<String, String> assigned(Value spec) {
        var assigned = new java.util.LinkedHashMap<String, String>();
        for (var item : ide.refactoring.ChangeSignature.split(spec == null ? "" : Value.asText(spec))) {
            var eq = item.indexOf('=');
            if (eq < 0) throw new IllegalArgumentException("not name = value: " + item);
            assigned.put(item.substring(0, eq).strip(), item.substring(eq + 1).strip());
        }
        return assigned;
    }

    /**
     * What a constructor is given, in the order it takes it: a parameter named in {@code named} the value made for it
     * (in {@code made}, as they are named), each other the next of {@code items}; each parameter given once.
     */
    private Value inOrder(Element constructor, List<Element> items, List<String> named, List<Value> made) {
        var params = graph.children(constructor.id()).stream().filter(e -> e.kind().name().equals("PARAM")).toList();
        var given = new java.util.ArrayList<Value>();
        var next = items.iterator();
        for (var param : params) {
            var at = named.indexOf(param.name());
            if (at >= 0) given.add(made.get(at));
            else if (next.hasNext()) given.add(new Value.Elem(next.next().id()));
            else throw new ide.projection.PrimitiveRejected("refactoring.precondition", constructor.qualified() + " is given no " + param.name());
        }
        if (next.hasNext() || named.stream().anyMatch(n -> params.stream().noneMatch(p -> p.name().equals(n)))) {
            throw new ide.projection.PrimitiveRejected("refactoring.precondition", constructor.qualified() + " takes " + params.size() + " values");
        }
        return new Value.Items(given);
    }

    /** The uses of an element; of several, those of each, in the order each site makes them. */
    private Value refs(List<Element> of) {
        var uses = of.size() == 1 ? graph.referencesTo(of.getFirst().id()) : of.stream()
                .flatMap(e -> graph.referencesTo(e.id()).stream()).map(Reference::site).distinct()
                .flatMap(site -> graph.referencesFrom(site).stream())
                .filter(r -> of.stream().anyMatch(e -> e.id().equals(r.target()))).toList();
        return new Value.Items(uses.stream().<Value>map(r -> new Value.Ref(r.id())).toList());
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
    /** What a use that binds nothing is: a {@code type}, a {@code call}, a {@code new} object, or a {@code value} read or written. */
    private static String used(Mode mode) {
        return mode == Mode.CALL ? "call" : mode == Mode.NEW ? "new" : mode.isTypeUse() ? "type" : "value";
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

    /**
     * What reads, through an object a constructor makes, the value given in {@code item}'s place among {@code items}: the
     * method whose body answers the field the constructor's body gives that parameter, as said in no language.
     */
    private Value getterOf(Element constructor, List<Element> items, Element item) {
        var params = graph.children(constructor.id()).stream().filter(e -> e.kind().name().equals("PARAM")).toList();
        var at = items.indexOf(item);
        if (at < 0 || at >= params.size()) throw new IllegalArgumentException(item.qualified() + " has no parameter of " + constructor.qualified());
        var param = params.get(at).id();
        var field = graph.children(constructor.id()).stream().filter(e -> e.kind().name().equals("STATEMENT"))
                .flatMap(s -> projection.said(s.id()).stream().flatMap(text -> ide.program.Body.parse(text, graph, constructor.id()).statements().stream()))
                .filter(s -> s instanceof ide.program.Body.Assign a && a.value() instanceof ide.program.Body.Parameter p && p.parameter().equals(param))
                .map(s -> ((ide.program.Body.Assign) s).field()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(constructor.qualified() + " gives " + params.get(at).name() + " to no field"));
        var read = "return " + ide.program.Body.name(field);
        return graph.members(constructor.parent()).stream().filter(m -> m.kind().name().equals("METHOD"))
                .filter(m -> { // its body is that one statement
                    var own = graph.children(m.id()).stream().filter(e -> e.kind().name().equals("STATEMENT")).toList();
                    return own.size() == 1 && projection.said(own.getFirst().id()).filter(read::equals).isPresent();
                }).findFirst().map(m -> elem(m.id()))
                .orElseThrow(() -> new IllegalArgumentException("no method of " + graph.element(constructor.parent()).qualified() + " answers " + graph.element(field).qualified()));
    }
}
