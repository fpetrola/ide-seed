package ide.refactor;

/** Naive: moves the field to the superclass of its owner. */
public final class PullUpField implements Refactoring {

    @Override
    public String id() {
        return "pull-up-field";
    }

    @Override
    public void apply(Ctx ctx, Args args) {
        var graph = ctx.program().graph();
        var field = args.element("field", graph);
        var superclass = graph.element(field.parent()).attribute("superclass");
        var target = graph.find(superclass)
                .orElseThrow(() -> new IllegalArgumentException("superclass not in workspace: " + superclass));
        ctx.program().move(field.id(), target.id());
    }
}
