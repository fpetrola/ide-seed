package ide.refactor;

import ide.graph.Kind;

/** {@code public void setX(T x) { this.x = x; }} for field {@code x} of type {@code T}. */
public final class GenerateSetter implements Refactoring {

    @Override
    public String id() {
        return "generate-setter";
    }

    @Override
    public String apply(Ctx ctx, Args args) {
        var program = ctx.program();
        var field = args.element("field", program.graph());
        var setter = program.create(Kind.METHOD, field.parent(), "set" + Names.cap(field.name()));
        program.set(setter, "visibility", "public");
        var param = program.create(Kind.PARAM, setter, field.name());
        program.set(param, "type", field.attribute("type"));
        program.set(setter, "body", "this.#{" + field.id() + "} = #{" + param + "};");
        return setter.value();
    }
}
