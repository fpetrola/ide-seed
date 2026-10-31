package ide.coherence;

import ide.graph.ElementId;
import ide.graph.Kind;
import ide.graph.Program;
import ide.graph.ProgramGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * A program under change. Primitives pass through; intended rebindings ({@link #retarget}) are folded into the
 * baseline so that the verifier expects them instead of reporting them.
 */
public final class Transaction implements Program {

    private final Program program;
    private final Object checkpoint;
    private ProgramGraph baseline;
    private final List<String> steps = new ArrayList<>();
    private int depth;

    public Transaction(Program program) {
        this.program = program;
        this.checkpoint = program.checkpoint();
        this.baseline = program.graph();
    }

    /** Verifies and repairs; keeps the changes if that reaches a fixpoint, else rolls back. */
    public Result commit(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, program);
        outcome.repairs().forEach(repair -> steps.add("! " + repair));
        if (outcome.violations().isEmpty()) return Result.ok(outcome.repairs(), steps);
        program.rollback(checkpoint);
        return Result.aborted(outcome.violations(), steps);
    }

    public Result abort(Violation reason) {
        program.rollback(checkpoint);
        return Result.aborted(List.of(reason), steps);
    }

    /** Marks the start of a refactoring call; primitives until {@link #exit()} are nested under it. */
    public void enter(String call) {
        log(". " + call);
        depth++;
    }

    public void exit() {
        depth--;
    }

    private void log(String step) {
        steps.add("  ".repeat(depth) + step);
    }

    private String name(ElementId id) {
        return program.graph().element(id).qualified();
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
        var id = program.create(kind, parent, name);
        log("' create " + kind + " " + name(id));
        return id;
    }

    @Override
    public void move(ElementId member, ElementId newParent, ElementId before) {
        var from = name(member);
        program.move(member, newParent, before);
        log("' move " + from + " -> " + name(newParent));
    }

    @Override
    public void rename(ElementId element, String name) {
        var from = name(element);
        program.rename(element, name);
        log("' rename " + from + " -> " + name);
    }

    @Override
    public void set(ElementId element, String attribute, String value) {
        program.set(element, attribute, value);
        log("' set " + name(element) + " " + attribute + " = " + value);
    }

    @Override
    public void retarget(String ref, ElementId target) {
        var reference = program.graph().reference(ref);
        program.retarget(ref, target);
        baseline = baseline.retargeted(reference.site(), reference.target(), target);
        log("' retarget " + ref + " " + reference.name() + " -> " + name(target));
    }
}
