package ide.rules;

import java.util.LinkedHashSet;
import java.util.Set;

/** What the body of a clause says: an atom that holds, one that does not, or two terms compared. */
public sealed interface Literal {

    /** The variables it names, anonymous ones apart. */
    Set<String> variables();

    record Positive(Atom atom) implements Literal {

        @Override
        public Set<String> variables() {
            return atom.variables();
        }

        @Override
        public String toString() {
            return atom.toString();
        }
    }

    /** Holds when no fact or rule says the atom; its anonymous variables mean "with any value". */
    record Negative(Atom atom) implements Literal {

        @Override
        public Set<String> variables() {
            return atom.variables();
        }

        @Override
        public String toString() {
            return "not " + atom;
        }
    }

    /** {@code left = right}, or {@code left != right} when {@code equal} is false. */
    record Comparison(Term left, boolean equal, Term right) implements Literal {

        @Override
        public Set<String> variables() {
            var variables = new LinkedHashSet<String>();
            for (var term : new Term[] {left, right}) if (term instanceof Term.Variable v && !v.anonymous()) variables.add(v.name());
            return variables;
        }

        /** Whether it holds once both sides have values. */
        public boolean holds(java.util.Map<String, String> binding) {
            return value(left, binding).equals(value(right, binding)) == equal;
        }

        private static String value(Term term, java.util.Map<String, String> binding) {
            return switch (term) {
                case Term.Constant c -> c.value();
                case Term.Variable v -> binding.get(v.name());
            };
        }

        @Override
        public String toString() {
            return left + (equal ? " = " : " != ") + right;
        }
    }
}
