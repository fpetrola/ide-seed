package ide.refactoring.script;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Program;
import ide.program.Reference;
import ide.projection.LanguageRules;
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

    ScriptFunctions(Program graph, LanguageRules rules) {
        this.graph = graph;
        this.rules = rules;
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
            case "superclass" -> graph.superclass(element(args, 0).id()).map(e -> elem(e.id())).orElse(null);
            case "enclosing" -> elem(graph.enclosingType(element(args, 0).id()).id());
            case "subclass-of" -> bool(graph.isSubclass(element(args, 0), element(args, 1)));
            case "member-named" -> graph.members(element(args, 0).id()).stream()
                    .filter(m -> m.name().equals(element(args, 1).name()) && m.kind() == element(args, 1).kind())
                    .map(m -> elem(m.id())).findFirst().orElse(null);
            case "field-in" -> graph.typesIn(text(args, 0)).stream()
                    .flatMap(t -> graph.members(t.id()).stream())
                    .filter(m -> m.kind().name().equals("FIELD") && m.name().equals(text(args, 1)))
                    .map(m -> elem(m.id())).findFirst().orElse(null);
            case "default-value" -> text(switch (text(args, 0)) {
                case "byte", "short", "int", "long", "float", "double" -> "0";
                case "boolean" -> "false";
                case "char" -> "'\\0'";
                default -> "null";
            });
            case "top-in" -> elem(graph.typesIn(text(args, 0)).getFirst().id());
            // language
            case "wider" -> {
                var all = rules.visibilities(graph, element(args, 0).id());
                yield texts(all.subList(all.indexOf(element(args, 0).fact(Fact.VISIBILITY)) + 1, all.size()));
            }
            case "unresolved-in" -> graph.unresolved().stream() // a use in the file at a path, binding nothing by that name
                    .filter(use -> use.name().equals(text(args, 1)) && text(args, 0).equals(graph.topLevel(use.site()).fact(Fact.FILE)))
                    .<Value>map(use -> new Value.Ref(use.id())).findFirst().orElse(null);
            case "types-named" -> elems(graph.types().stream().filter(type -> type.name().equals(text(args, 0))).toList());
            case "is-type-name" -> bool(!text(args, 0).isEmpty() && Character.isUpperCase(text(args, 0).charAt(0)));
            // queries
            case "members" -> elems(graph.members(element(args, 0).id()));
            case "fields" -> elems(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("FIELD")).toList());
            case "methods" -> elems(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("METHOD")).toList());
            case "params" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("PARAM")).toList());
            case "constructors" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("CONSTRUCTOR")).toList());
            case "body" -> text(element(args, 0).fact(Fact.BODY));
            case "types-of" -> elems(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().isType()).toList());
            case "subclasses" -> elems(graph.subclasses(element(args, 0).id()));
            case "family" -> elems(graph.family(element(args, 0)));
            case "refs" -> new Value.Items(graph.referencesTo(element(args, 0).id()).stream().<Value>map(r -> new Value.Ref(r.id())).toList());
            // references
            case "mode" -> text(reference(args.getFirst()).mode().label());
            case "site" -> elem(reference(args.getFirst()).site());
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

    private Reference reference(Value value) {
        if (!(value instanceof Value.Ref ref)) throw new IllegalArgumentException("not a reference: " + Value.asText(value));
        return graph.reference(ref.id());
    }

    private static String text(List<Value> args, int index) {
        return Value.asText(args.get(index));
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

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
