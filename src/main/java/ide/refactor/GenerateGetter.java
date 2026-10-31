package ide.refactor;

import ide.graph.Kind;

/** {@code public T getX() { return x; }} for field {@code x} of type {@code T}. */
public final class GenerateGetter implements Refactoring {

    @Override
    public String id() {
        return "generate-getter";
    }

    @Override
    public String apply(Ctx ctx, Args args) {
        var program = ctx.program();
        var field = args.element("field", program.graph());
        var getter = program.create(Kind.METHOD, field.parent(), "get" + Names.cap(field.name()));
        program.set(getter, "visibility", "public");
        program.set(getter, "type", field.attribute("type"));
        program.set(getter, "body", "return #{" + field.id() + "};");
        return getter.value();
    }
}
