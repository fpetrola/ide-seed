package ide.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A relation said of some terms: {@code parent(X, "bea")}. */
public record Atom(String relation, List<Term> terms) {

    public Atom {
        terms = List.copyOf(terms);
    }

    public static Atom of(String relation, Term... terms) {
        return new Atom(relation, List.of(terms));
    }

    /** The variables it names, in the order it names them, anonymous ones apart. */
    public Set<String> variables() {
        var variables = new LinkedHashSet<String>();
        for (var term : terms) if (term instanceof Term.Variable v && !v.anonymous()) variables.add(v.name());
        return variables;
    }

    /** What is known of each place once {@code binding} is: the value, or null where any will do. */
    public List<String> pattern(Map<String, String> binding) {
        var pattern = new ArrayList<String>(terms.size());
        for (var term : terms) {
            pattern.add(switch (term) {
                case Term.Constant c -> c.value();
                case Term.Variable v -> binding.get(v.name());
            });
        }
        return pattern;
    }

    /** {@code binding} extended so that this atom says {@code tuple}, or null if it cannot. */
    public Map<String, String> unify(List<String> tuple, Map<String, String> binding) {
        if (tuple.size() != terms.size()) return null;
        Map<String, String> extended = null;
        for (var i = 0; i < terms.size(); i++) {
            var value = tuple.get(i);
            switch (terms.get(i)) {
                case Term.Constant c -> {
                    if (!c.value().equals(value)) return null;
                }
                case Term.Variable v -> {
                    if (v.anonymous()) continue;
                    var known = extended != null ? extended.get(v.name()) : binding.get(v.name());
                    if (known != null) {
                        if (!known.equals(value)) return null;
                    } else {
                        if (extended == null) extended = new java.util.HashMap<>(binding);
                        extended.put(v.name(), value);
                    }
                }
            }
        }
        return extended != null ? extended : binding;
    }

    @Override
    public String toString() {
        return relation + "(" + String.join(", ", terms.stream().map(Term::toString).toList()) + ")";
    }
}
