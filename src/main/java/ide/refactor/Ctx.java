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

    /** Language semantics, for scripts that ask (e.g. which visibilities are wider). */
    ide.graph.LanguageRules rules();

    /** The declared parameters of a refactoring, to bind positional arguments. */
    List<Param> params(String refactoring);
}
