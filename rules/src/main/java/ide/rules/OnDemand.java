package ide.rules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Evaluates on demand, from the query down: a relation is computed for what is known of it (its pattern) and
 * remembered for the life of the evaluation; one that depends on itself is computed whole, to its fixpoint. A body is
 * read left to right, and a negation or a comparison waits until its variables have values, which an atom gives them
 * (the rules are safe). The facts are asked only with the values already known.
 */
final class OnDemand implements Engine {

    @Override
    public Evaluation evaluate(Rules rules, Facts facts) {
        return new Run(rules, facts);
    }

    private static final class Run implements Evaluation {

        private final Rules rules;
        private final Facts facts;
        private final Map<String, List<List<String>>> tables = new HashMap<>();
        private final Map<String, Set<List<String>>> fixpoints = new HashMap<>();

        Run(Rules rules, Facts facts) {
            this.rules = rules;
            this.facts = facts;
        }

        @Override
        public List<Map<String, String>> answers(Atom query) {
            var answers = new LinkedHashSet<Map<String, String>>();
            for (var tuple : tuples(query.relation(), query.pattern(Map.of()))) {
                var binding = query.unify(tuple, Map.of());
                if (binding != null) answers.add(Map.copyOf(binding));
            }
            return List.copyOf(answers);
        }

        @Override
        public Evaluation suppose(Collection<Tuple> added, Collection<Tuple> removed) {
            for (var tuples : List.of(added, removed)) {
                for (var tuple : tuples) {
                    if (rules.defines(tuple.relation())) {
                        throw new IllegalArgumentException("rules define " + tuple.relation() + ": only facts can be supposed");
                    }
                }
            }
            return new Run(rules, facts.with(added, removed));
        }

        private List<List<String>> tuples(String relation, List<String> pattern) {
            if (!rules.defines(relation)) return facts.match(relation, pattern).toList();
            if (rules.recursive(relation)) return fixpoint(relation).stream().filter(t -> Facts.matches(t, pattern)).toList();
            var key = relation + pattern;
            var known = tables.get(key);
            if (known != null) return known;
            var found = new LinkedHashSet<List<String>>();
            for (var clause : rules.defining(relation)) {
                var binding = bind(clause.head(), pattern);
                if (binding != null) solve(clause.body(), binding, b -> found.add(tuple(clause.head(), b)));
            }
            var answer = List.copyOf(found);
            tables.put(key, answer);
            return answer;
        }

        /** A relation that depends on itself, with every relation it depends on itself with: computed whole. */
        private Set<List<String>> fixpoint(String relation) {
            if (!fixpoints.containsKey(relation)) {
                var component = rules.component(relation);
                for (var member : component) fixpoints.put(member, new LinkedHashSet<>());
                boolean grew;
                do {
                    grew = false;
                    for (var member : component) {
                        for (var clause : rules.defining(member)) {
                            var produced = new ArrayList<List<String>>();
                            solve(clause.body(), Map.of(), b -> produced.add(tuple(clause.head(), b)));
                            if (fixpoints.get(member).addAll(produced)) grew = true;
                        }
                    }
                } while (grew);
            }
            return fixpoints.get(relation);
        }

        private void solve(List<Literal> pending, Map<String, String> binding, Consumer<Map<String, String>> out) {
            if (pending.isEmpty()) {
                out.accept(binding);
                return;
            }
            var index = ready(pending, binding);
            var literal = pending.get(index);
            var rest = new ArrayList<>(pending);
            rest.remove(index);
            switch (literal) {
                case Literal.Positive p -> {
                    for (var tuple : tuples(p.atom().relation(), p.atom().pattern(binding))) {
                        var extended = p.atom().unify(tuple, binding);
                        if (extended != null) solve(rest, extended, out);
                    }
                }
                case Literal.Negative n -> {
                    if (tuples(n.atom().relation(), n.atom().pattern(binding)).isEmpty()) solve(rest, binding, out);
                }
                case Literal.Comparison c -> {
                    if (c.holds(binding)) solve(rest, binding, out);
                }
            }
        }

        /** The first literal that can be read now: an atom, or a negation or a comparison whose variables have values. */
        private static int ready(List<Literal> pending, Map<String, String> binding) {
            for (var i = 0; i < pending.size(); i++) {
                var literal = pending.get(i);
                if (literal instanceof Literal.Positive || binding.keySet().containsAll(literal.variables())) return i;
            }
            throw new IllegalStateException("no literal can be read: " + pending);
        }

        /** The binding that makes {@code head} say what {@code pattern} knows, or null if it cannot. */
        private static Map<String, String> bind(Atom head, List<String> pattern) {
            var binding = new HashMap<String, String>();
            for (var i = 0; i < head.terms().size(); i++) {
                var value = pattern.get(i);
                if (value == null) continue;
                switch (head.terms().get(i)) {
                    case Term.Constant c -> {
                        if (!c.value().equals(value)) return null;
                    }
                    case Term.Variable v -> {
                        var known = binding.putIfAbsent(v.name(), value);
                        if (known != null && !known.equals(value)) return null;
                    }
                }
            }
            return binding;
        }

        private static List<String> tuple(Atom head, Map<String, String> binding) {
            var values = new ArrayList<String>(head.terms().size());
            for (var term : head.terms()) {
                values.add(switch (term) {
                    case Term.Constant c -> c.value();
                    case Term.Variable v -> binding.get(v.name());
                });
            }
            return values;
        }
    }
}
