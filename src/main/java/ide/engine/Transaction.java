package ide.engine;

import ide.program.ElementId;
import ide.program.Kind;
import ide.projection.Projection;
import ide.program.Program;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A program under change. Primitives pass through; intended rebindings ({@link #retarget}) are folded into the
 * baseline so that the verifier expects them instead of reporting them.
 */
public final class Transaction implements Projection {

    private final Projection projection;
    private final Object checkpoint;
    private Program baseline;
    private final boolean compiled;
    private final List<String> steps = new ArrayList<>();
    private final List<String> search = new ArrayList<>();
    private final java.util.LinkedHashSet<String> obligations = new java.util.LinkedHashSet<>();
    /** Elements the refactoring changed on purpose; repairs may not undo that. */
    private final Map<ElementId, java.util.Set<String>> pinned = new java.util.HashMap<>();
    private int depth;

    public Transaction(Projection projection) {
        this.projection = projection;
        this.checkpoint = projection.checkpoint();
        this.baseline = projection.graph();
        this.compiled = projection.diagnostics().isEmpty();
    }

    /** Verifies and repairs; keeps the changes if that reaches a fixpoint, else rolls back. */
    public Result commit(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, compiled, projection, pinned);
        outcome.repairs().forEach(repair -> steps.add("! " + repair));
        search.addAll(outcome.search());
        if (!compiled) obligations.add("types were not checked: the code did not compile before");
        if (outcome.violations().isEmpty()) return Result.ok(outcome.repairs(), steps, search, List.copyOf(obligations));
        projection.rollback(checkpoint);
        return Result.aborted(outcome.violations(), steps, search);
    }

    /** A point inside the transaction to come back to: projection state and the baseline's expectations. */
    public record Mark(Object checkpoint, Program baseline, int steps) {
    }

    public Mark mark() {
        return new Mark(projection.checkpoint(), baseline, steps.size());
    }

    /** Undoes everything since the mark, keeping the steps that were logged (they show what was attempted). */
    public void back(Mark mark) {
        projection.rollback(mark.checkpoint());
        baseline = mark.baseline();
    }

    /** Verifies and repairs now; returns the first violation left, or null if the state is coherent. */
    public Violation check(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, compiled, projection, pinned);
        outcome.repairs().forEach(repair -> log("! " + repair));
        search.addAll(outcome.search());
        return outcome.violations().isEmpty() ? null : outcome.violations().getFirst();
    }

    /** Something the refactoring cannot decide and assumes; the result's guarantee becomes "assumed". */
    public void oblige(String assumption) {
        obligations.add(assumption);
        log(": " + assumption);
    }

    /** Records a discarded attempt. */
    public void discarded(String reason) {
        log("X " + reason);
    }

    /** The state before the transaction, for undo. */
    public Object before() {
        return checkpoint;
    }

    /** Rolls back; {@code reason} may be null when nothing went wrong (e.g. an expansion). */
    public Result abort(Violation reason) {
        projection.rollback(checkpoint);
        return Result.aborted(reason == null ? List.of() : List.of(reason), steps, search);
    }

    /** Marks the start of a refactoring call; primitives until {@link #exit()} are nested under it. */
    public void enter(String call) {
        log(". " + call);
        depth++;
    }

    public void exit() {
        depth--;
    }

    private void pin(ElementId element, String... aspects) {
        pinned.computeIfAbsent(element, e -> new java.util.HashSet<>()).addAll(List.of(aspects));
    }

    private void log(String step) {
        steps.add("  ".repeat(depth) + step);
    }

    private String name(ElementId id) {
        return projection.graph().element(id).qualified();
    }

    @Override
    public Program graph() {
        return projection.graph();
    }

    @Override
    public List<ide.projection.Diagnostic> diagnostics() {
        return projection.diagnostics();
    }

    @Override
    public java.util.Optional<ide.projection.CompletionContext> completionContext(String path, String text, int offset) {
        return projection.completionContext(path, text, offset);
    }

    @Override
    public Object checkpoint() {
        return projection.checkpoint();
    }

    @Override
    public void rollback(Object checkpoint) {
        projection.rollback(checkpoint);
    }

    @Override
    public ElementId create(Kind kind, ElementId parent, String name) {
        var id = projection.create(kind, parent, name);
        if (kind != ide.program.Kind.PACKAGE) pin(id, "parent", "name");
        log("' create " + kind + " " + name(id));
        return id;
    }

    @Override
    public void move(ElementId member, ElementId newParent, ElementId before) {
        var from = name(member);
        projection.move(member, newParent, before);
        pin(member, "parent");
        log("' move " + from + " -> " + name(newParent));
    }

    @Override
    public void replaceSource(String path, String text) {
        projection.replaceSource(path, text);
        log("' edit " + path);
    }

    /** Keeps a manual edit as it is: the user's text is the authority, there is nothing to verify against. */
    public Result keep() {
        return Result.ok(List.of(), steps, search, List.copyOf(obligations));
    }

    @Override
    public void delete(ElementId element) {
        var name = name(element);
        projection.delete(element);
        baseline = baseline.withoutUsesFrom(element); // what it used goes with it
        log("' delete " + name);
    }

    @Override
    public void rename(ElementId element, String name) {
        var from = name(element);
        projection.rename(element, name);
        pin(element, "name");
        log("' rename " + from + " -> " + name);
    }

    @Override
    public void set(ElementId element, String attribute, String value) {
        var oldSuperclass = attribute.equals("superclass")
                ? projection.graph().find(String.valueOf(projection.graph().element(element).attribute("superclass"))) : null;
        var before = projection.graph().element(element);
        var oldDeclared = attribute.equals("type") ? projection.graph().find(String.valueOf(before.attribute("declared"))) : null;
        projection.set(element, attribute, value);
        if (oldDeclared != null && oldDeclared.isPresent()) { // the declaration now names another type, on purpose
            var site = before.kind() == ide.program.Kind.PARAM ? before.parent() : element;
            var now = projection.graph().element(element).attribute("declared");
            projection.graph().find(String.valueOf(now))
                    .ifPresent(to -> baseline = baseline.retargeted(site, oldDeclared.get().id(), to.id()));
        }
        if (attribute.equals("visibility")) pin(element, "visibility");
        if (oldSuperclass != null && oldSuperclass.isPresent()) { // an intended rebinding of the extends clause
            projection.graph().find(value).ifPresent(to -> baseline = baseline.retargeted(element, oldSuperclass.get().id(), to.id()));
        }
        log("' set " + name(element) + " " + attribute + " = " + value);
    }

    @Override
    public ElementId extract(ElementId from, String fragment, String name) {
        var where = name(from);
        var created = projection.extract(from, fragment, name);
        for (var moved : projection.graph().references()) { // what the fragment used is now used from the new method
            if (moved.site().equals(created)) baseline = baseline.resited(from, moved.target(), created);
        }
        log("' extract " + where + " " + fragment + " -> " + name(created));
        return created;
    }

    @Override
    public void inline(String call) {
        var reference = projection.graph().reference(call);
        projection.inline(call);
        baseline = baseline.without(reference.site(), reference.target()); // the call is meant to go
        log("' inline " + call + " " + reference.name());
    }

    @Override
    public void retarget(String ref, ElementId target) {
        var reference = projection.graph().reference(ref);
        projection.retarget(ref, target);
        baseline = baseline.retargeted(reference.site(), reference.target(), target);
        log("' retarget " + ref + " " + reference.name() + " -> " + name(target));
    }
}
