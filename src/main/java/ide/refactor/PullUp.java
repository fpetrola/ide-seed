package ide.refactor;

/** Naive: moves a member to the superclass of its owner. */
public final class PullUp implements Refactoring {

    private final String id;
    private final String parameter;

    private PullUp(String id, String parameter) {
        this.id = id;
        this.parameter = parameter;
    }

    public static PullUp field() {
        return new PullUp("pull-up-field", "field");
    }

    public static PullUp method() {
        return new PullUp("pull-up-method", "method");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String apply(Ctx ctx, Args args) {
        var graph = ctx.program().graph();
        var member = args.element(parameter, graph);
        var superclass = graph.element(member.parent()).attribute("superclass");
        var target = graph.find(superclass)
                .orElseThrow(() -> new IllegalArgumentException("superclass not in workspace: " + superclass));
        ctx.program().move(member.id(), target.id());
        return null;
    }
}
