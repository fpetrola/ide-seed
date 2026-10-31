package ide.refactor;

import ide.graph.Program;

import java.util.Map;

/** What a refactoring sees while it runs inside a transaction. */
public interface Ctx {

    Program program();

    /** Applies another refactoring in the same transaction; returns what it produced (an element id) or null. */
    String call(String refactoring, Map<String, ?> args);
}
