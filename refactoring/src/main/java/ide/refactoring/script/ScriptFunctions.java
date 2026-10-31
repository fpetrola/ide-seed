package ide.refactoring.script;

import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.projection.LanguageRules;
import ide.program.Program;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The functions and filters scripts can use. Values are strings (element ids, reference ids, text), lists and
 * booleans. Elements may also be given by address.
 */
final class ScriptFunctions {

    private final Program graph;
    private final LanguageRules rules;

    ScriptFunctions(Program graph, LanguageRules rules) {
        this.graph = graph;
        this.rules = rules;
    }

    Object call(String name, List<Object> args) {
        return switch (name) {
            // elements
            case "name" -> element(args, 0).name();
            case "kind" -> element(args, 0).kind().name();
            case "qualified" -> element(args, 0).qualified();
            case "owner" -> id(element(args, 0).parent());
            case "top" -> graph.topLevel(element(args, 0).id()).id().value();
            case "attr" -> element(args, 0).fact(Fact.of(text(args, 1)));
            case "type" -> element(args, 0).fact(Fact.TYPE);
            case "superclass" -> graph.superclass(element(args, 0).id()).map(Element::qualified).orElse(null);
            case "enclosing" -> graph.enclosingType(element(args, 0).id()).id().value();
            case "subclass-of" -> graph.isSubclass(element(args, 0), element(args, 1));
            case "member-named" -> graph.members(element(args, 0).id()).stream()
                    .filter(m -> m.name().equals(element(args, 1).name()) && m.kind() == element(args, 1).kind())
                    .map(m -> m.id().value()).findFirst().orElse(null);
            case "field-in" -> graph.typesIn(text(args, 0)).stream()
                    .flatMap(t -> graph.members(t.id()).stream())
                    .filter(m -> m.kind().name().equals("FIELD") && m.name().equals(text(args, 1)))
                    .map(m -> m.id().value()).findFirst().orElse(null);
            case "default-value" -> switch (text(args, 0)) {
                case "byte", "short", "int", "long", "float", "double" -> "0";
                case "boolean" -> "false";
                case "char" -> "'\\0'";
                default -> "null";
            };
            case "top-in" -> graph.typesIn(text(args, 0)).getFirst().id().value();
            // language
            case "wider" -> {
                var all = rules.visibilities(graph, element(args, 0).id());
                yield all.subList(all.indexOf(element(args, 0).fact(Fact.VISIBILITY)) + 1, all.size());
            }
            case "import-candidates" -> rules.importCandidates(graph, text(args, 0));
            case "is-type-name" -> !text(args, 0).isEmpty() && Character.isUpperCase(text(args, 0).charAt(0));
            // queries
            case "members" -> ids(graph.members(element(args, 0).id()));
            case "fields" -> ids(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("FIELD")).toList());
            case "methods" -> ids(graph.members(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("METHOD")).toList());
            case "params" -> ids(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("PARAM")).toList());
            case "paramnames" -> graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("PARAM")).map(Element::name).toList();
            case "constructors" -> ids(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().name().equals("CONSTRUCTOR")).toList());
            case "body" -> element(args, 0).fact(Fact.BODY);
            case "types-of" -> ids(graph.children(element(args, 0).id()).stream().filter(e -> e.kind().isType()).toList());
            case "subclasses" -> ids(graph.subclasses(element(args, 0).id()));
            case "family" -> ids(graph.family(element(args, 0)));
            case "refs" -> graph.referencesTo(element(args, 0).id()).stream().map(r -> r.id()).toList();
            // references
            case "mode" -> graph.reference(text(args, 0)).mode().label();
            case "site" -> graph.reference(text(args, 0)).site().value();
            case "target" -> graph.reference(text(args, 0)).target().value();
            // text and logic
            case "cap" -> cap(text(args, 0));
            case "decap" -> text(args, 0).isEmpty() ? "" : Character.toLowerCase(text(args, 0).charAt(0)) + text(args, 0).substring(1);
            case "join" -> String.join(text(args, 1), list(args.get(0)).stream().map(String::valueOf).toList());
            case "concat" -> String.join("", args.stream().map(String::valueOf).toList());
            case "eq" -> String.valueOf(args.get(0)).equals(String.valueOf(args.get(1)));
            case "ne" -> !String.valueOf(args.get(0)).equals(String.valueOf(args.get(1)));
            case "not" -> !truthy(args.get(0));
            case "and" -> args.stream().allMatch(ScriptFunctions::truthy);
            case "or" -> args.stream().anyMatch(ScriptFunctions::truthy);
            case "empty" -> !truthy(args.get(0));
            case "nonempty" -> truthy(args.get(0));
            case "count" -> list(args.get(0)).size();
            case "first" -> list(args.get(0)).isEmpty() ? null : list(args.get(0)).getFirst();
            default -> throw new IllegalArgumentException("unknown script function: " + name);
        };
    }

    boolean filter(String name, Object item, Object arg) {
        return switch (name) {
            case "within" -> within(item, String.valueOf(arg));
            case "not-within" -> !within(item, String.valueOf(arg));
            case "of-kind" -> element(item).kind().name().equals(String.valueOf(arg));
            case "named" -> element(item).name().equals(String.valueOf(arg));
            case "with-attr" -> {
                var spec = String.valueOf(arg);
                var eq = spec.indexOf('=');
                yield spec.substring(eq + 1).equals(element(item).fact(Fact.of(spec.substring(0, eq))));
            }
            default -> throw new IllegalArgumentException("unknown script filter: " + name);
        };
    }

    /** Whether an element, or the site of a reference, is (inside) {@code container}. */
    private boolean within(Object item, String container) {
        var value = String.valueOf(item);
        var start = value.contains("@") ? graph.reference(value).site() : element(item).id();
        var target = element(container).id();
        for (var e = graph.element(start); ; e = graph.element(e.parent())) {
            if (e.id().equals(target)) return true;
            if (e.parent() == null) return false;
        }
    }

    static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean b -> b;
            case String s -> !s.isEmpty();
            case Collection<?> c -> !c.isEmpty();
            default -> true;
        };
    }

    static List<?> list(Object value) {
        if (value instanceof List<?> list) return list;
        if (value == null) return List.of();
        return List.of(value);
    }

    private Element element(List<Object> args, int index) {
        return element(args.get(index));
    }

    private Element element(Object value) {
        var text = String.valueOf(value);
        var id = new ElementId(text);
        if (graph.contains(id)) return graph.element(id);
        return graph.find(text).orElseThrow(() -> new IllegalArgumentException("no element " + text));
    }

    private static String text(List<Object> args, int index) {
        return String.valueOf(args.get(index));
    }

    private static String id(ElementId id) {
        return id == null ? null : id.value();
    }

    private static List<String> ids(List<Element> elements) {
        return new ArrayList<>(elements.stream().map(e -> e.id().value()).toList());
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
