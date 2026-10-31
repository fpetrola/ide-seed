package ide.refactor;

/** Moves a top-level type to another (existing) package. */
public final class MoveType implements Refactoring {

    @Override
    public String id() {
        return "move-type";
    }

    @Override
    public String apply(Ctx ctx, Args args) {
        var graph = ctx.program().graph();
        var type = args.element("type", graph);
        ctx.program().move(type.id(), args.element("package", graph).id());
        return type.id().value();
    }
}
