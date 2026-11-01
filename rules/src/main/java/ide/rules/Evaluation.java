package ide.rules;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Rules over facts, asked. */
public interface Evaluation {

    /** The answers to a query, each a binding of its variables: in the order they are found, without repeats. */
    List<Map<String, String>> answers(Atom query);

    default boolean holds(Atom atom) {
        return !answers(atom).isEmpty();
    }

    /**
     * The same rules over the same facts with {@code added} and without {@code removed}: a state that is only
     * supposed, as cheap to drop as to make. Only facts can be supposed, not what rules define.
     */
    Evaluation suppose(Collection<Tuple> added, Collection<Tuple> removed);
}
