package ide.refactor;

/** The rename primitive as a refactoring. */
public final class Rename implements Refactoring {

    @Override
    public String id() {
        return "rename";
    }

    @Override
    public void apply(Ctx ctx, Args args) {
        ctx.program().rename(args.element("element", ctx.program().graph()).id(), args.text("name"));
    }
}
