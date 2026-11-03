package ide.refactoring;

import ide.core.Param;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one composite written in Java, because its parameter is a list of records, written as in Java:
 * {@code "int b, int a, long scale = 1"} keeps {@code b} and {@code a} in that order, adds {@code scale} (calls pass
 * {@code 1}) and removes the rest. A kept parameter may be written by its name alone, and {@code scale:long=1} says
 * the same as {@code long scale = 1}. A new parameter needs a value for the calls only if there are calls.
 * Only primitives underneath: create (with the argument calls pass), move and delete of parameters.
 */
public final class ChangeSignature implements Refactoring {

    private record Item(String name, String type, String defaultValue) {
    }

    @Override
    public String id() {
        return "change-signature";
    }

    @Override
    public String description() {
        return "Reorders, adds (with a default for the calls) and removes parameters of a method, and changes its visibility";
    }

    @Override
    public List<Param> params() {
        return List.of(Param.of("method", "element", "METHOD"), new Param("parameters", "text", List.of(),
                "the new parameters, as in Java: int b, int a, long scale = 1 (a new one gives the value calls pass)", true),
                Param.of("visibility", "text").optional());
    }

    /** The parameters the method has now, as they would be written to keep them, and its visibility. */
    @Override
    public Map<String, String> suggestions(Run run, Args args) {
        if (args.values().get("method") == null || args.values().get("parameters") != null) return Map.of();
        var method = args.element("method", run.program());
        return Map.of("parameters", String.join(", ", params(run, method.id()).stream().map(p -> p.fact(Fact.TYPE) + " " + p.name()).toList()),
                "visibility", method.fact(Fact.VISIBILITY));
    }

    @Override
    public ElementId apply(Run run, Args args) {
        var projection = run.projection();
        var method = args.element("method", projection.program()).id();
        var items = parse(args.text("parameters"));
        for (var item : items) {
            if (param(run, method, item.name()) != null) continue;
            if (item.type() == null) throw new IllegalArgumentException("a new parameter needs its type: " + item.name());
            projection.apply(new Primitive.Create(Kind.PARAM, method, item.name(), Map.of(Fact.TYPE, item.type()), item.defaultValue()));
        }
        for (var i = 0; i < items.size(); i++) { // each kept parameter goes before the one now at its place
            var wanted = param(run, method, items.get(i).name());
            var there = params(run, method).get(i);
            if (!there.equals(wanted)) projection.apply(new Primitive.Move(wanted.id(), method, there.id()));
        }
        var kept = items.stream().map(Item::name).toList();
        for (var param : params(run, method)) {
            if (!kept.contains(param.name())) projection.apply(new Primitive.Delete(param.id()));
        }
        if (args.values().get("visibility") != null && !args.text("visibility").equals(run.program().element(method).fact(Fact.VISIBILITY))) {
            projection.apply(new Primitive.Set(method, "visibility", args.text("visibility")));
        }
        return method;
    }

    private static List<Element> params(Run run, ElementId method) {
        return run.program().children(method).stream().filter(p -> p.kind() == Kind.PARAM).toList();
    }

    private static Element param(Run run, ElementId method, String name) {
        return run.program().children(method).stream()
                .filter(p -> p.kind() == Kind.PARAM && p.name().equals(name)).findFirst().orElse(null);
    }

    /** {@code type name = value}, {@code name:type=value} or a name alone. */
    private static List<Item> parse(String spec) {
        var items = new ArrayList<Item>();
        for (var raw : split(spec)) {
            var eq = raw.indexOf('=');
            var value = eq < 0 ? null : raw.substring(eq + 1).strip();
            var declared = (eq < 0 ? raw : raw.substring(0, eq)).strip();
            var colon = declared.indexOf(':');
            if (colon >= 0) {
                items.add(new Item(declared.substring(0, colon).strip(), declared.substring(colon + 1).strip(), value));
            } else {
                var space = declared.lastIndexOf(' ');
                items.add(space < 0 ? new Item(declared, null, value)
                        : new Item(declared.substring(space + 1), declared.substring(0, space).strip(), value));
            }
        }
        return items;
    }

    /**
     * The items of a spec: split at the commas outside brackets and quotes, and before a default value, outside the
     * {@code <>} of a type (in a value, {@code <} compares).
     */
    public static List<String> split(String spec) {
        var parts = new ArrayList<String>();
        var depth = 0;
        var types = 0;
        var value = false;
        var quote = (char) 0;
        var start = 0;
        for (var i = 0; i < spec.length(); i++) {
            var c = spec.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == '<' && !value) types++;
            else if (c == '>' && !value) types--;
            else if (c == '=' && depth == 0 && types == 0) value = true;
            else if (c == ',' && depth == 0 && types == 0) {
                parts.add(spec.substring(start, i));
                start = i + 1;
                value = false;
            }
        }
        parts.add(spec.substring(start));
        return parts.stream().map(String::strip).filter(part -> !part.isEmpty()).toList();
    }
}
