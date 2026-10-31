package ide.refactor;

import ide.core.Param;
import ide.graph.Program;

import java.util.List;
import java.util.Map;

/** What a refactoring sees while it runs inside a transaction. */
public interface Ctx {

    Program program();

    /** Applies another refactoring in the same transaction; returns what it produced (an element id) or null. */
    String call(String refactoring, Map<String, ?> args);

    /**
     * Runs {@code body} as an attempt: if it is rejected or the result does not verify (after repairs), everything
     * it did is undone, the attempt is recorded as discarded and the transaction goes on. Returns whether it stayed.
     */
    boolean attempt(String label, Runnable body);

    /** Declares an assumption the refactoring cannot verify; it travels with the result as an obligation. */
    void oblige(String assumption);

    /** Language semantics, for scripts that ask (e.g. which visibilities are wider). */
    ide.graph.LanguageRules rules();

    /** The declared parameters of a refactoring, to bind positional arguments. */
    List<Param> params(String refactoring);
}
