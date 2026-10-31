package ide.refactor;

import ide.graph.Kind;

import java.util.Map;

/** Creates a class between a type and its superclass and pulls the chosen fields up into it. */
public final class ExtractSuperclass implements Refactoring {

    @Override
    public String id() {
        return "extract-superclass";
    }

    @Override
    public void apply(Ctx ctx, Args args) {
        var program = ctx.program();
        var type = args.element("type", program.graph());
        var fields = args.elements("fields", program.graph());
        var oldSuperclass = type.attribute("superclass");

        var base = program.create(Kind.CLASS, type.parent(), args.text("name"));
        if (oldSuperclass != null) program.set(base, "superclass", oldSuperclass);
        program.set(type.id(), "superclass", program.graph().element(base).qualified());
        for (var field : fields) ctx.call("pull-up-field", Map.of("field", program.graph().element(field.id()).qualified()));
    }
}
