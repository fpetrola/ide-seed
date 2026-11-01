package ide.rules;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the relations no rule defines come from, asked by pattern: a view over whatever holds them, never a copy. A
 * pattern has a value where the asker knows one and null where any will do.
 */
@FunctionalInterface
public interface Facts {

    Stream<List<String>> match(String relation, List<String> pattern);

    static Facts none() {
        return (relation, pattern) -> Stream.empty();
    }

    /** Facts held in memory, in the order given. */
    static Facts of(Collection<Tuple> tuples) {
        var all = List.copyOf(tuples);
        return (relation, pattern) -> all.stream().filter(t -> t.relation().equals(relation) && matches(t.values(), pattern))
                .map(Tuple::values);
    }

    /** These facts, with {@code added} and without {@code removed}. */
    default Facts with(Collection<Tuple> added, Collection<Tuple> removed) {
        var gone = new HashSet<>(removed);
        var more = List.copyOf(added);
        return (relation, pattern) -> Stream.concat(
                        match(relation, pattern).filter(values -> !gone.contains(new Tuple(relation, values))),
                        more.stream().filter(t -> t.relation().equals(relation) && matches(t.values(), pattern)).map(Tuple::values))
                .distinct();
    }

    /** These facts and those of {@code other}, relation by relation. */
    default Facts and(Facts other) {
        return (relation, pattern) -> Stream.concat(match(relation, pattern), other.match(relation, pattern)).distinct();
    }

    /** Whether {@code values} say what {@code pattern} knows. */
    static boolean matches(List<String> values, List<String> pattern) {
        if (values.size() != pattern.size()) return false;
        for (var i = 0; i < values.size(); i++) {
            if (pattern.get(i) != null && !pattern.get(i).equals(values.get(i))) return false;
        }
        return true;
    }
}
