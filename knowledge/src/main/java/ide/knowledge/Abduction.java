package ide.knowledge;

import ide.rules.Atom;
import ide.rules.Clause;
import ide.rules.Evaluation;
import ide.rules.Literal;
import ide.rules.Rules;
import ide.rules.Term;
import ide.rules.Tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What it would take for a predicate that definitions say to hold: for each definition, the changes to the graph and
 * the predicates to make first, or the literal where it cannot go on. A body is read left to right. What the graph
 * has, asked with what is known, binds the rest; an atom of the graph whose arguments are all known and that does
 * not hold is made to hold, if a primitive changes its relation; one of a defined relation is made first, with its
 * own definitions. Negations and comparisons are only checked. Nothing here knows a language: the relations a
 * primitive changes are the graph's ({@link #CHANGED}).
 */
final class Abduction {

    /**
     * The relations a primitive changes, each keyed by its first argument (one value at a time): a visibility is
     * set, a parent is moved to, a use is retargeted. {@code before(a, b)} is a move of {@code a} in front of
     * {@code b}. A constructor nobody has is created ({@link #CREATED}).
     */
    static final Set<String> CHANGED = Set.of("visibility", "parent", "target", "before");
    static final Set<String> CREATED = Set.of("constructor");

    /** A tuple made to hold, and the one it takes the place of (the relation's former value for the same key), if any. */
    record Change(String relation, List<String> values, List<String> was) {
    }

    /** One way a definition holds: its changes, in the order they were found, and what it needs made first. */
    record Found(List<Change> changes, List<Atom> needs) {
    }

    /** A definition tried: the ways it holds, or the first literal it could not get past. */
    record Attempt(Clause clause, List<Found> found, Literal stuck) {
    }

    private final Rules rules;
    private final Evaluation evaluation;
    private int created;

    Abduction(Rules rules, Evaluation evaluation) {
        this.rules = rules;
        this.evaluation = evaluation;
    }

    /** Each definition of {@code predicate}, tried for {@code args}, in the order they are written. */
    List<Attempt> attempts(String predicate, List<String> args) {
        var attempts = new ArrayList<Attempt>();
        for (var clause : rules.defining(predicate)) {
            var binding = bind(clause.head(), args);
            if (binding == null) continue;
            var found = new ArrayList<Found>();
            var stuck = new Literal[1];
            explore(clause.body(), binding, List.of(), List.of(), evaluation, found, stuck);
            attempts.add(new Attempt(clause, found, found.isEmpty() ? stuck[0] : null));
        }
        return attempts;
    }

    private void explore(List<Literal> pending, Map<String, String> binding, List<Change> changes, List<Atom> needs,
                         Evaluation state, List<Found> found, Literal[] stuck) {
        if (pending.isEmpty()) {
            if (!changes.isEmpty() || !needs.isEmpty()) found.add(new Found(changes, needs));
            return;
        }
        var index = ready(pending, binding);
        var literal = pending.get(index);
        var rest = new ArrayList<>(pending);
        rest.remove(index);
        switch (literal) {
            case Literal.Positive p -> {
                var atom = bound(p.atom(), binding);
                var known = atom.variables().isEmpty() && atom.terms().stream().noneMatch(t -> t instanceof Term.Variable);
                if (known && state.holds(atom)) {
                    explore(rest, binding, changes, needs, state, found, stuck);
                } else if (known && rules.defines(atom.relation())) {
                    explore(rest, binding, changes, plus(needs, atom), state, found, stuck);
                } else if (known && CHANGED.contains(atom.relation())) {
                    var change = change(atom, state);
                    explore(rest, binding, plus(changes, change), needs, suppose(state, change), found, stuck);
                } else if (!known) {
                    var answers = state.answers(atom);
                    for (var answer : answers) {
                        var extended = new HashMap<>(binding);
                        extended.putAll(answer);
                        explore(rest, extended, changes, needs, state, found, stuck);
                    }
                    if (answers.isEmpty() && CREATED.contains(atom.relation()) && creatable(atom)) {
                        var made = "new:" + atom.relation() + ":" + ++created;
                        var values = new ArrayList<String>();
                        values.add(made);
                        for (var term : atom.terms().subList(1, atom.terms().size())) values.add(((Term.Constant) term).value());
                        var change = new Change(atom.relation(), values, null);
                        var extended = new HashMap<>(binding);
                        extended.put(((Term.Variable) atom.terms().getFirst()).name(), made);
                        explore(rest, extended, plus(changes, change), needs, suppose(state, change), found, stuck);
                    } else if (answers.isEmpty()) {
                        stuck(stuck, new Literal.Positive(atom));
                    }
                } else {
                    stuck(stuck, new Literal.Positive(atom));
                }
            }
            case Literal.Negative n -> {
                var atom = bound(n.atom(), binding);
                if (state.holds(atom)) stuck(stuck, new Literal.Negative(atom));
                else explore(rest, binding, changes, needs, state, found, stuck);
            }
            case Literal.Comparison c -> {
                if (c.holds(binding)) explore(rest, binding, changes, needs, state, found, stuck);
                else stuck(stuck, bound(c, binding));
            }
        }
    }

    /** The change that makes a tuple of a changed relation hold: it takes the place of the key's former value. */
    private static Change change(Atom atom, Evaluation state) {
        var values = atom.terms().stream().map(t -> ((Term.Constant) t).value()).toList();
        if (atom.relation().equals("before")) return new Change(atom.relation(), values, null);
        var former = state.answers(new Atom(atom.relation(), List.of(atom.terms().getFirst(), Term.variable("Was"))));
        return new Change(atom.relation(), values, former.isEmpty() ? null : List.of(values.getFirst(), former.getFirst().get("Was")));
    }

    /** A creation knows everything but the element it creates, the first argument. */
    private static boolean creatable(Atom atom) {
        return atom.terms().getFirst() instanceof Term.Variable v && !v.anonymous()
                && atom.terms().stream().skip(1).allMatch(t -> t instanceof Term.Constant);
    }

    private static Evaluation suppose(Evaluation state, Change change) {
        var added = List.of(new Tuple(change.relation(), change.values()));
        List<Tuple> removed = change.was() == null ? List.of() : List.of(new Tuple(change.relation(), change.was()));
        return state.suppose(added, removed);
    }

    private static void stuck(Literal[] stuck, Literal literal) {
        if (stuck[0] == null) stuck[0] = literal;
    }

    /** The first literal that can be read now: an atom, or a negation or a comparison whose variables have values. */
    private static int ready(List<Literal> pending, Map<String, String> binding) {
        for (var i = 0; i < pending.size(); i++) {
            var literal = pending.get(i);
            if (literal instanceof Literal.Positive || binding.keySet().containsAll(literal.variables())) return i;
        }
        throw new IllegalStateException("no literal can be read: " + pending);
    }

    private static Map<String, String> bind(Atom head, List<String> args) {
        if (head.terms().size() != args.size()) return null;
        var binding = new HashMap<String, String>();
        for (var i = 0; i < args.size(); i++) {
            switch (head.terms().get(i)) {
                case Term.Constant c -> {
                    if (!c.value().equals(args.get(i))) return null;
                }
                case Term.Variable v -> {
                    if (v.anonymous()) continue;
                    var known = binding.putIfAbsent(v.name(), args.get(i));
                    if (known != null && !known.equals(args.get(i))) return null;
                }
            }
        }
        return binding;
    }

    /** The atom with the values known so far in place of its variables. */
    static Atom bound(Atom atom, Map<String, String> binding) {
        return new Atom(atom.relation(), atom.terms().stream().map(t -> bound(t, binding)).toList());
    }

    private static Literal bound(Literal.Comparison c, Map<String, String> binding) {
        return new Literal.Comparison(bound(c.left(), binding), c.equal(), bound(c.right(), binding));
    }

    private static Term bound(Term term, Map<String, String> binding) {
        return term instanceof Term.Variable v && binding.containsKey(v.name()) ? Term.constant(binding.get(v.name())) : term;
    }

    private static <T> List<T> plus(List<T> list, T item) {
        var more = new ArrayList<>(list);
        more.add(item);
        return List.copyOf(more);
    }
}
