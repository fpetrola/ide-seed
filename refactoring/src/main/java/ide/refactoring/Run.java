package ide.refactoring;

import ide.core.Param;
import ide.engine.Trace;
import ide.engine.Transaction;
import ide.program.ElementId;
import ide.program.Program;
import ide.projection.LanguageRules;
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;

import java.util.List;
import java.util.Map;

/**
 * What a refactoring sees while it runs: the program and its rules, the refactorings it can call, attempts it can
 * drop, assumptions it can make. There is one kind of run and two places it happens:
 * <ul>
 *   <li>Inside a transaction (what the session runs): calls nest in the transaction's trace, an attempt that is
 *   refused or does not verify is undone, an assumption becomes an obligation of the result.</li>
 *   <li>Straight on a projection (a plan the repairer judges as a whole): primitives go to the projection, an attempt
 *   is undone only when refused, and assuming is refused, since a repair that cannot decide is no repair.</li>
 * </ul>
 */
public final class Run {

    private final Library library;
    private final Projection projection;
    private final Transaction transaction;

    private Run(Library library, Projection projection, Transaction transaction) {
        this.library = library;
        this.projection = projection;
        this.transaction = transaction;
    }

    /** A run inside a transaction, which judges its attempts. */
    public static Run in(Transaction transaction, Library library) {
        return new Run(library, transaction, transaction);
    }

    /** A run straight on a projection. */
    public static Run on(Projection projection, Library library) {
        return new Run(library, projection, null);
    }

    /** Where primitives go. */
    public Projection projection() {
        return projection;
    }

    public Program program() {
        return projection.program();
    }

    public LanguageRules rules() {
        return projection.rules();
    }

    /** Runs another refactoring in this run; answers the element it produced or worked on, or null. */
    public ElementId call(String refactoring, Map<String, ?> args) {
        var called = library.get(refactoring);
        if (transaction == null || called.level().equals("primitive")) return called.apply(this, new Args(args));
        transaction.enter(refactoring);
        try {
            return called.apply(this, new Args(args));
        } finally {
            transaction.exit();
        }
    }

    /** The declared parameters of a refactoring, to bind positional arguments. */
    public List<Param> params(String refactoring) {
        return library.get(refactoring).params();
    }

    /** Runs {@code body} as an attempt that is undone if it fails; answers whether it stayed. */
    public boolean attempt(String label, Runnable body) {
        if (transaction != null) return transaction.attempt(label, body);
        var saved = projection.snapshot();
        try {
            body.run();
            return true;
        } catch (PrimitiveRejected refused) {
            projection.restore(saved);
            return false;
        }
    }

    /** Makes an intent's want hold, as the library's planner deduces; refused if the library has none. */
    public void want(String intent, String want, Map<String, Value> variables) {
        var planner = library.planner();
        if (planner == null) throw new PrimitiveRejected("intent.unplanned", "nothing plans " + intent + ": an intent needs a planner");
        planner.want(intent, want, variables, this);
    }

    /** How a planner deduced what it made: inside a transaction it goes with the result's search; else nowhere. */
    public void searched(Trace search) {
        if (transaction != null) transaction.searched(search);
    }

    /** Declares what the refactoring cannot verify; it travels with the result as an obligation. */
    public void oblige(String assumption) {
        if (transaction == null) throw new PrimitiveRejected("plan.assumes", "a repair cannot assume that " + assumption);
        transaction.oblige(assumption);
    }
}
