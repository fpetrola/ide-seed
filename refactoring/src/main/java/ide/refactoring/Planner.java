package ide.refactoring;

import java.util.Map;

/**
 * What makes an intent's want hold: it deduces the changes the want asks for and what they break, and makes them in a
 * run. The want is written in the planner's language, which a script does not read. The knowledge is one; a library
 * with none cannot run an intent.
 */
@FunctionalInterface
public interface Planner {

    /** Makes {@code want}, over the intent's parameters as values, hold in {@code run}. */
    void want(String intent, String want, Map<String, Value> variables, Run run);
}
