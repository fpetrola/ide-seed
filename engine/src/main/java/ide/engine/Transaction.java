package ide.engine;

import ide.program.Change;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.Projection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A program under change. Every primitive passes through {@link #apply}: the projection applies it and declares its
 * change, whose expectations are folded into the baseline (the verifier expects them instead of reporting them) and
 * whose pins repairs may not undo. The transaction knows nothing of what a primitive means.
 */
public final class Transaction implements Projection {

    private final Projection projection;
    private final Snapshot start;
    private Program baseline;
    private final boolean compiled;
    private final Trace trace = new Trace();
    private final Trace search = new Trace();
    private final Set<String> obligations = new LinkedHashSet<>();
    private final Set<Change.Pin> pinned = new LinkedHashSet<>();

    public Transaction(Projection projection) {
        this.projection = projection;
        this.start = projection.snapshot();
        this.baseline = projection.program();
        this.compiled = projection.diagnostics().isEmpty();
    }

    /** Verifies and repairs; keeps the changes if that reaches a fixpoint, else restores the start. */
    public Result commit(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, compiled, projection, pinned);
        outcome.repairs().forEach(repair -> trace.add(Trace.Kind.ANSWER, repair));
        search.append(outcome.search());
        if (!compiled) obligations.add("types were not checked: the code did not compile before");
        if (outcome.violations().isEmpty()) return Result.ok(outcome.repairs(), trace, search, List.copyOf(obligations));
        projection.restore(start);
        return Result.aborted(outcome.violations(), trace, search);
    }

    /** A point inside the transaction to come back to: the projection's state and the baseline's expectations. */
    public record Mark(Snapshot snapshot, Program baseline) {
    }

    public Mark mark() {
        return new Mark(projection.snapshot(), baseline);
    }

    /** Undoes everything since the mark, keeping the steps that were logged (they show what was attempted). */
    public void back(Mark mark) {
        projection.restore(mark.snapshot());
        baseline = mark.baseline();
    }

    /** Verifies and repairs now; returns the first violation left, or null if the state is coherent. */
    public Violation check(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, compiled, projection, pinned);
        outcome.repairs().forEach(repair -> trace.add(Trace.Kind.ANSWER, repair));
        search.append(outcome.search());
        return outcome.violations().isEmpty() ? null : outcome.violations().getFirst();
    }

    /** Something the refactoring cannot decide and assumes; the result's guarantee becomes "assumed". */
    public void oblige(String assumption) {
        obligations.add(assumption);
        trace.add(Trace.Kind.ASSUMED, assumption);
    }

    /** Records a discarded attempt. */
    public void discarded(String reason) {
        trace.add(Trace.Kind.DROPPED, reason);
    }

    /** The state before the transaction, for undo. */
    public Snapshot before() {
        return start;
    }

    /** Restores the start; {@code reason} may be null when nothing went wrong (e.g. an expansion). */
    public Result abort(Violation reason) {
        projection.restore(start);
        return Result.aborted(reason == null ? List.of() : List.of(reason), trace, search);
    }

    /** Keeps a manual edit as it is: the user's text is the authority, there is nothing to verify against. */
    public Result keep() {
        return Result.ok(List.of(), trace, search, List.copyOf(obligations));
    }

    /** Marks the start of a refactoring call; primitives until {@link #exit()} are nested under it. */
    public void enter(String call) {
        trace.open(Trace.Kind.CALL, call);
    }

    public void exit() {
        trace.close();
    }

    @Override
    public Change apply(Primitive primitive) {
        var before = projection.program();
        var change = projection.apply(primitive);
        baseline = baseline.expecting(change);
        pinned.addAll(change.pins());
        trace.add(Trace.Kind.PRIMITIVE, describe(primitive, before, projection.program(), change));
        return change;
    }

    @Override
    public void edit(String path, String text) {
        projection.edit(path, text);
        trace.add(Trace.Kind.PRIMITIVE, "edit " + path);
    }

    /** A primitive as the steps show it: elements by their address before it, and what it produced after. */
    private static String describe(Primitive primitive, Program before, Program after, Change change) {
        return switch (primitive) {
            case Primitive.Create c -> "create " + c.kind() + " " + after.element(change.produced()).qualified();
            case Primitive.Delete d -> "delete " + before.element(d.element()).qualified();
            case Primitive.Move m -> "move " + before.element(m.element()).qualified() + " -> " + after.element(m.parent()).qualified();
            case Primitive.Rename r -> "rename " + before.element(r.element()).qualified() + " -> " + r.name();
            case Primitive.Set s -> "set " + after.element(s.element()).qualified() + " " + s.attribute() + " = " + s.value();
            case Primitive.Link l -> "link " + after.element(l.element()).qualified() + " " + l.mode().label()
                    + " " + after.element(l.target()).qualified();
            case Primitive.Retarget r -> "retarget " + r.reference() + " " + before.reference(r.reference()).name()
                    + " -> " + after.element(r.target()).qualified();
            case Primitive.Extract e -> "extract " + before.element(e.method()).qualified() + " " + e.fragment()
                    + " -> " + after.element(change.produced()).qualified();
            case Primitive.Inline i -> "inline " + i.reference() + " " + before.reference(i.reference()).name();
        };
    }

    @Override
    public Program program() {
        return projection.program();
    }

    @Override
    public Snapshot snapshot() {
        return projection.snapshot();
    }

    @Override
    public void restore(Snapshot snapshot) {
        projection.restore(snapshot);
    }

    @Override
    public ide.projection.LanguageRules rules() {
        return projection.rules();
    }

    @Override
    public List<Diagnostic> diagnostics() {
        return projection.diagnostics();
    }

    @Override
    public Optional<CompletionContext> completionContext(String path, String text, int offset) {
        return projection.completionContext(path, text, offset);
    }
}
