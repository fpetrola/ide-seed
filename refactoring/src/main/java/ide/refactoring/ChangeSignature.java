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
 * The one composite written in Java, because its parameter is a list of records: {@code "b, a, scale:int=1"} keeps
 * {@code b} and {@code a} in that order, adds {@code scale} (calls pass {@code 1}) and removes the rest.
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
        return "Reorders, adds (with a default for the calls) and removes parameters of a method";
    }

    @Override
    public List<Param> params() {
        return List.of(Param.of("method", "element", "METHOD"), Param.of("parameters", "text"));
    }

    @Override
    public ElementId apply(Run run, Args args) {
        var projection = run.projection();
        var method = args.element("method", projection.program()).id();
        var items = parse(args.text("parameters"));
        for (var item : items) {
            if (param(run, method, item.name()) != null) continue;
            if (item.type() == null || item.defaultValue() == null) {
                throw new IllegalArgumentException("new parameter needs name:type=default: " + item.name());
            }
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
        return method;
    }

    private static List<Element> params(Run run, ElementId method) {
        return run.program().children(method).stream().filter(p -> p.kind() == Kind.PARAM).toList();
    }

    private static Element param(Run run, ElementId method, String name) {
        return run.program().children(method).stream()
                .filter(p -> p.kind() == Kind.PARAM && p.name().equals(name)).findFirst().orElse(null);
    }

    private static List<Item> parse(String spec) {
        var items = new ArrayList<Item>();
        for (var raw : spec.split(",")) {
            var text = raw.strip();
            var colon = text.indexOf(':');
            var eq = text.indexOf('=');
            if (colon < 0) {
                items.add(new Item(text, null, null));
            } else {
                items.add(new Item(text.substring(0, colon).strip(),
                        text.substring(colon + 1, eq < 0 ? text.length() : eq).strip(),
                        eq < 0 ? null : text.substring(eq + 1).strip()));
            }
        }
        return items;
    }
}
