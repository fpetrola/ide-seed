package ide.refactor;

import java.util.Map;

/** Renames a method together with every method it overrides or that overrides it. */
public final class RenameMethodFamily implements Refactoring {

    @Override
    public String id() {
        return "rename-method-family";
    }

    @Override
    public void apply(Ctx ctx, Args args) {
        var graph = ctx.program().graph();
        var name = args.text("name");
        for (var member : graph.family(args.element("method", graph))) {
            ctx.call("rename", Map.of("element", ctx.program().graph().element(member.id()).qualified(), "name", name));
        }
    }
}
