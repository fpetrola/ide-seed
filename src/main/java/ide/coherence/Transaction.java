package ide.coherence;

import ide.graph.ElementId;
import ide.graph.Kind;
import ide.graph.Program;
import ide.graph.ProgramGraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A program under change. Primitives pass through; intended rebindings ({@link #retarget}) are folded into the
 * baseline so that the verifier expects them instead of reporting them.
 */
public final class Transaction implements Program {

    private final Program program;
    private final Object checkpoint;
    private ProgramGraph baseline;
    private final boolean compiled;
    private final List<String> steps = new ArrayList<>();
    private final List<String> search = new ArrayList<>();
    private final java.util.LinkedHashSet<String> obligations = new java.util.LinkedHashSet<>();
    /** Elements the refactoring changed on purpose; repairs may not undo that. */
    private final Map<ElementId, java.util.Set<String>> pinned = new java.util.HashMap<>();
    private int depth;

    public Transaction(Program program) {
        this.program = program;
        this.checkpoint = program.checkpoint();
        this.baseline = program.graph();
        this.compiled = program.diagnostics().isEmpty();
    }

    /** Verifies and repairs; keeps the changes if that reaches a fixpoint, else rolls back. */
    public Result commit(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, compiled, program, pinned);
        outcome.repairs().forEach(repair -> steps.add("! " + repair));
        search.addAll(outcome.search());
        if (!compiled) obligations.add("types were not checked: the code did not compile before");
        if (outcome.violations().isEmpty()) return Result.ok(outcome.repairs(), steps, search, List.copyOf(obligations));
        program.rollback(checkpoint);
        return Result.aborted(outcome.violations(), steps, search);
    }

    /** A point inside the transaction to come back to: program state and the baseline's expectations. */
    public record Mark(Object checkpoint, ProgramGraph baseline, int steps) {
    }

    public Mark mark() {
        return new Mark(program.checkpoint(), baseline, steps.size());
    }

    /** Undoes everything since the mark, keeping the steps that were logged (they show what was attempted). */
    public void back(Mark mark) {
        program.rollback(mark.checkpoint());
        baseline = mark.baseline();
    }

    /** Verifies and repairs now; returns the first violation left, or null if the state is coherent. */
    public Violation check(Repairer repairer) {
        var outcome = repairer.stabilize(baseline, compiled, program, pinned);
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
        program.rollback(checkpoint);
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
        return program.graph().element(id).qualified();
    }

    @Override
    public ProgramGraph graph() {
        return program.graph();
    }

    @Override
    public List<ide.graph.Diagnostic> diagnostics() {
        return program.diagnostics();
    }

    @Override
    public java.util.Optional<ide.graph.CompletionContext> completionContext(String path, String text, int offset) {
        return program.completionContext(path, text, offset);
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
        if (kind != ide.graph.Kind.PACKAGE) pin(id, "parent", "name");
        log("' create " + kind + " " + name(id));
        return id;
    }

    @Override
    public void move(ElementId member, ElementId newParent, ElementId before) {
        var from = name(member);
        program.move(member, newParent, before);
        pin(member, "parent");
        log("' move " + from + " -> " + name(newParent));
    }

    @Override
    public void replaceSource(String path, String text) {
        program.replaceSource(path, text);
        log("' edit " + path);
    }

    /** Keeps a manual edit as it is: the user's text is the authority, there is nothing to verify against. */
    public Result keep() {
        return Result.ok(List.of(), steps, search, List.copyOf(obligations));
    }

    @Override
    public void delete(ElementId element) {
        var name = name(element);
        program.delete(element);
        baseline = baseline.withoutUsesFrom(element); // what it used goes with it
        log("' delete " + name);
    }

    @Override
    public void rename(ElementId element, String name) {
        var from = name(element);
        program.rename(element, name);
        pin(element, "name");
        log("' rename " + from + " -> " + name);
    }

    @Override
    public void set(ElementId element, String attribute, String value) {
        var oldSuperclass = attribute.equals("superclass")
                ? program.graph().find(String.valueOf(program.graph().element(element).attribute("superclass"))) : null;
        var before = program.graph().element(element);
        var oldDeclared = attribute.equals("type") ? program.graph().find(String.valueOf(before.attribute("declared"))) : null;
        program.set(element, attribute, value);
        if (oldDeclared != null && oldDeclared.isPresent()) { // the declaration now names another type, on purpose
            var site = before.kind() == ide.graph.Kind.PARAM ? before.parent() : element;
            var now = program.graph().element(element).attribute("declared");
            program.graph().find(String.valueOf(now))
                    .ifPresent(to -> baseline = baseline.retargeted(site, oldDeclared.get().id(), to.id()));
        }
        if (attribute.equals("visibility")) pin(element, "visibility");
        if (oldSuperclass != null && oldSuperclass.isPresent()) { // an intended rebinding of the extends clause
            program.graph().find(value).ifPresent(to -> baseline = baseline.retargeted(element, oldSuperclass.get().id(), to.id()));
        }
        log("' set " + name(element) + " " + attribute + " = " + value);
    }

    @Override
    public ElementId extract(ElementId from, String fragment, String name) {
        var where = name(from);
        var created = program.extract(from, fragment, name);
        for (var moved : program.graph().references()) { // what the fragment used is now used from the new method
            if (moved.site().equals(created)) baseline = baseline.resited(from, moved.target(), created);
        }
        log("' extract " + where + " " + fragment + " -> " + name(created));
        return created;
    }

    @Override
    public void inline(String call) {
        var reference = program.graph().reference(call);
        program.inline(call);
        baseline = baseline.without(reference.site(), reference.target()); // the call is meant to go
        log("' inline " + call + " " + reference.name());
    }

    @Override
    public void retarget(String ref, ElementId target) {
        var reference = program.graph().reference(ref);
        program.retarget(ref, target);
        baseline = baseline.retargeted(reference.site(), reference.target(), target);
        log("' retarget " + ref + " " + reference.name() + " -> " + name(target));
    }
}
