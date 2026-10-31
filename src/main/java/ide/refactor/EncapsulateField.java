package ide.refactor;

import ide.graph.ElementId;

import java.util.Map;

/** Accessors for a field; uses outside its top-level type go through them; the field becomes private. */
public final class EncapsulateField implements Refactoring {

    @Override
    public String id() {
        return "encapsulate-field";
    }

    @Override
    public String apply(Ctx ctx, Args args) {
        var program = ctx.program();
        var field = args.element("field", program.graph());
        var address = field.qualified();
        var getter = new ElementId(ctx.call("generate-getter", Map.of("field", address)));
        var setter = new ElementId(ctx.call("generate-setter", Map.of("field", address)));
        var owner = program.graph().topLevel(field.id()).id();
        for (var ref : program.graph().referencesTo(field.id())) {
            if (program.graph().topLevel(ref.site()).id().equals(owner)) continue;
            program.retarget(ref.id(), ref.mode().equals("write") || ref.mode().equals("update") ? setter : getter);
        }
        program.set(field.id(), "visibility", "private");
        return field.id().value();
    }
}
