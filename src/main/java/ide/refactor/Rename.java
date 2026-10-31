package ide.refactor;

/** The rename primitive as a refactoring. */
public final class Rename implements Refactoring {

    @Override
    public String id() {
        return "rename";
    }

    @Override
    public String apply(Ctx ctx, Args args) {
        var element = args.element("element", ctx.program().graph()).id();
        ctx.program().rename(element, args.text("name"));
        return element.value();
    }
}
