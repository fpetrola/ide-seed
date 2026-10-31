package ide.coherence;

import ide.graph.ElementId;
import ide.graph.Kind;
import ide.graph.Program;
import ide.graph.ProgramGraph;

/**
 * A program under change. Primitives pass through; intended rebindings ({@link #retarget}) are folded into the
 * baseline so that the verifier expects them instead of reporting them.
 */
public final class Transaction implements Program {

    private final Program program;
    private final Object checkpoint;
    private ProgramGraph baseline;

    public Transaction(Program program) {
        this.program = program;
        this.checkpoint = program.checkpoint();
        this.baseline = program.graph();
    }

    /** Verifies and repairs; keeps the changes if that reaches a fixpoint, else rolls back. */
    public Result commit(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, program);
        if (outcome.violations().isEmpty()) return Result.ok(outcome.repairs());
        program.rollback(checkpoint);
        return Result.aborted(outcome.violations());
    }

    public Result abort(Violation reason) {
        program.rollback(checkpoint);
        return Result.aborted(java.util.List.of(reason));
    }

    @Override
    public ProgramGraph graph() {
        return program.graph();
    }

    @Override
    public Object checkpoint() {
        return program.checkpoint();
    }

    @Override
    public void rollback(Object checkpoint) {
        program.rollback(checkpoint);
    }

    @Override
    public ElementId create(Kind kind, ElementId parent, String name) {
        return program.create(kind, parent, name);
    }

    @Override
    public void move(ElementId member, ElementId newParent, ElementId before) {
        program.move(member, newParent, before);
    }

    @Override
    public void rename(ElementId element, String name) {
        program.rename(element, name);
    }

    @Override
    public void set(ElementId element, String attribute, String value) {
        program.set(element, attribute, value);
    }

    @Override
    public void retarget(String ref, ElementId target) {
        var reference = program.graph().reference(ref);
        program.retarget(ref, target);
        baseline = baseline.retargeted(reference.site(), reference.target(), target);
    }
}
