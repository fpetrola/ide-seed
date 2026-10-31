package ide.refactor;

import ide.core.Param;
import ide.graph.Element;
import ide.graph.ElementId;
import ide.graph.Kind;

import java.util.ArrayList;
import java.util.List;

/**
 * The one composite written in Java, because its parameter is a list of records: {@code "b, a, scale:int=1"} keeps
 * {@code b} and {@code a} in that order, adds {@code scale} (calls pass {@code 1}) and removes the rest.
 * Only primitives underneath: create/set(type, default, order)/delete of parameters.
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
    public String apply(Ctx ctx, Args args) {
        var program = ctx.program();
        var method = args.element("method", program.graph()).id();
        var items = parse(args.text("parameters"));
        for (var item : items) {
            if (param(ctx, method, item.name()) != null) continue;
            if (item.type() == null || item.defaultValue() == null) {
                throw new IllegalArgumentException("new parameter needs name:type=default: " + item.name());
            }
            var created = program.create(Kind.PARAM, method, item.name());
            program.set(created, "type", item.type());
            program.set(created, "default", item.defaultValue());
        }
        for (var i = 0; i < items.size(); i++) program.set(param(ctx, method, items.get(i).name()).id(), "order", String.valueOf(i));
        var kept = items.stream().map(Item::name).toList();
        for (var param : List.copyOf(program.graph().children(method))) {
            if (!kept.contains(param.name())) program.delete(param.id());
        }
        return method.value();
    }

    private static Element param(Ctx ctx, ElementId method, String name) {
        return ctx.program().graph().children(method).stream()
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
