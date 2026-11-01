package ide.rules;

import java.util.List;

/** One fact: a relation and its values. */
public record Tuple(String relation, List<String> values) {

    public Tuple {
        values = List.copyOf(values);
    }

    @Override
    public String toString() {
        return relation + values;
    }
}
