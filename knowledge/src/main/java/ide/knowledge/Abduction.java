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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What it would take for a predicate that definitions say to hold, or for a rule to hold where it is broken. Nothing
 * here knows a language: the relations a primitive changes are the graph's ({@link #CHANGED}, {@link #TOGGLED},
 * {@link #CREATED}), and what may not change is the knowledge's (promises).
 * <p>
 * To make a predicate hold, each definition's body is read left to right. What the graph has, asked with what is
 * known, binds the rest; an atom of the graph whose arguments are all known and that does not hold is made to hold if a
 * primitive changes its relation; one of a defined relation is made first, with its own definitions.
 * <p>
 * To make a rule hold where it is broken ({@code violates-<rule>}), one of what the broken instance stands on is
 * taken away: a fact it needs removed, or one it needs absent added.
 * <p>
 * Either way, every rule is asked again around what changed: what the changes break is mended in the same plan, never
 * by undoing one of its own changes. A promise is broken only on purpose: as the first change of a plan, the one the
 * plan is about, which then changes meaning; never as a consequence. A plan is at most {@link #MOST_CHANGES} changes.
 */
final class Abduction {

    static final int MOST_CHANGES = 4;
    private static final String UNDO = "it would undo ";

    /** Relations a primitive sets, keyed by their first argument: a visibility, a parent, what a use binds, a place, a type. */
    static final Set<String> CHANGED = Set.of("visibility", "parent", "target", "before", "type");
    /**
     * Relations a primitive turns on or off: {@code static(e)}, {@code final(e)}, {@code abstract(e)}; and
     * {@code initialized(f)}, which a value given where the field is declared turns on.
     */
    static final Set<String> TOGGLED = Set.of("static", "final", "abstract", "initialized");
    /** Relations a tuple of which goes away when something is created: {@code unimplemented(t, m)}, by implementing m in t. */
    static final Set<String> UNDONE_BY_CREATING = Set.of("unimplemented");
    /** Relations a primitive takes away and never sets: {@code exists(e)}, which deleting e takes away. */
    static final Set<String> REMOVED = Set.of("exists");
    /**
     * Relations whose first argument a primitive creates, when nothing is there: {@code constructor(k, t)} (one with no
     * parameters), {@code member(e, owner, name, kind)}, {@code parameter(p, callable, name, what calls pass)} and
     * {@code constructor-for(k, type, use)} (one taking what a use passes).
     */
    static final Set<String> CREATED = Set.of("constructor", "member", "parameter", "constructor-for");
    /** Relations made to hold by several primitives at once: {@code takes-arguments(m, use)}, a parameter for each argument. */
    static final Set<String> COMPOSED = Set.of("takes-arguments");
    /** What a created element is called until it is: {@code new:<relation>:<n>}. */
    static final String NEW = "new:";
    private static final java.util.concurrent.atomic.AtomicInteger CREATIONS = new java.util.concurrent.atomic.AtomicInteger();

    /** A tuple made to hold, and the one it takes the place of; or one taken away; for what; the promise it breaks. */
    record Change(String relation, List<String> added, List<String> removed, String forWhat, String promise) {

        Change(String relation, List<String> added, List<String> removed, String forWhat) {
            this(relation, added, removed, forWhat, null);
        }

        String element() {
            return (added != null ? added : removed).getFirst();
        }

        Change breaking(String promise) {
            return new Change(relation, added, removed, forWhat, promise);
        }
    }

    /** Whether a change may be made: {@code promise} is what it breaks, made on purpose; {@code why} why it may not. */
    private record Verdict(boolean ok, String promise, String why) {
    }

    /** A predicate to make hold (or, if not {@code holds}, not hold) first, by its own definitions and effects. */
    record Need(Atom atom, boolean holds) {
    }

    /** One way: its changes, in the order they run, and what it needs made first. */
    record Found(List<Change> changes, List<Need> needs) {
    }

    /** A way given up: the changes it had made, and why it could not go on. */
    record Dead(List<Change> changes, String why) {
    }

    /** The ways found, and those given up. */
    record Ways(List<Found> found, List<Dead> dead) {
    }

    /** A definition tried: the ways it holds (each mended of what it breaks), or the first literal it could not get past. */
    record Attempt(Clause clause, List<Found> found, Literal stuck, List<Dead> dead) {
    }

    private final Rules rules;
    private final Set<Literal> conditions;
    private final Evaluation base;
    private final Map<String, String> promises;
    private final Map<String, Integer> violations = new HashMap<>();
    private final Shown shown;

    /** How an atom is shown in a reason: elements by their address. */
    interface Shown {
        String of(Atom atom);

        String of(String constant);
    }

    Abduction(Rules rules, Set<Literal> conditions, Evaluation base, Map<String, String> promises, Shown shown) {
        this.rules = rules;
        this.conditions = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        this.conditions.addAll(conditions);
        this.base = base;
        this.promises = promises;
        this.shown = shown;
        for (var clause : rules.clauses()) {
            if (clause.head().relation().startsWith("violates-")) violations.put(clause.head().relation(), clause.head().terms().size());
        }
    }

    // ---- making a predicate hold

    /** Each definition of {@code predicate}, tried for {@code args}, in the order they are written. */
    List<Attempt> attempts(String predicate, List<String> args) {
        var attempts = new ArrayList<Attempt>();
        for (var clause : rules.defining(predicate)) {
            var binding = bind(clause.head(), args);
            if (binding == null) continue;
            var found = new ArrayList<Found>();
            var stuck = new Literal[1];
            explore(predicate, clause.body(), binding, List.of(), List.of(), base, found, stuck);
            var dead = new ArrayList<Dead>();
            var mended = new ArrayList<Found>();
            for (var way : found) mended.addAll(mend(way, dead));
            attempts.add(new Attempt(clause, mended, found.isEmpty() ? stuck[0] : null, dead));
        }
        return attempts;
    }

    private void explore(String forWhat, List<Literal> pending, Map<String, String> binding, List<Change> changes,
                         List<Need> needs, Evaluation state, List<Found> found, Literal[] stuck) {
        if (pending.isEmpty()) {
            if (!changes.isEmpty() || !needs.isEmpty()) found.add(new Found(changes, needs));
            return;
        }
        var index = ready(pending, binding);
        var literal = pending.get(index);
        var rest = new ArrayList<>(pending);
        rest.remove(index);
        if (conditions.contains(literal)) { // a condition is only checked: what it asks binds, what it says must hold
            var answers = solutions(List.of(literal), binding, state);
            for (var answer : answers) explore(forWhat, rest, answer, changes, needs, state, found, stuck);
            if (answers.isEmpty()) stuck(stuck, switch (literal) {
                case Literal.Positive p -> new Literal.Positive(bound(p.atom(), binding));
                case Literal.Negative n -> new Literal.Negative(bound(n.atom(), binding));
                case Literal.Comparison c -> bound(c, binding);
            });
            return;
        }
        switch (literal) {
            case Literal.Positive p -> {
                var atom = bound(p.atom(), binding);
                var known = ground(atom);
                if (known && state.holds(atom)) {
                    explore(forWhat, rest, binding, changes, needs, state, found, stuck);
                } else if (known && rules.defines(atom.relation()) && created(atom)) {
                    // what is being created is made to hold in the same supposition, by the relation's definitions
                    var tried = false;
                    for (var clause : rules.defining(atom.relation())) {
                        var renamed = renamed(clause);
                        var head = bind(renamed.head(), values(atom));
                        if (head == null) continue;
                        tried = true;
                        var extended = new HashMap<>(binding);
                        extended.putAll(head);
                        var body = new ArrayList<Literal>(renamed.body());
                        body.addAll(rest);
                        explore(forWhat, body, extended, changes, needs, state, found, stuck);
                    }
                    if (!tried) stuck(stuck, new Literal.Positive(atom));
                } else if (known && COMPOSED.contains(atom.relation())) {
                    var change = new Change(atom.relation(), values(atom), null, forWhat);
                    explore(forWhat, rest, binding, plus(changes, change), needs, suppose(state, change), found, stuck);
                } else if (known && rules.defines(atom.relation())) {
                    explore(forWhat, rest, binding, changes, plus(needs, new Need(atom, true)), state, found, stuck);
                } else if (known && (CHANGED.contains(atom.relation()) || TOGGLED.contains(atom.relation()))) {
                    var change = set(atom, state, forWhat);
                    var verdict = allowed(change, changes);
                    if (verdict.ok()) {
                        change = change.breaking(verdict.promise());
                        explore(forWhat, rest, binding, plus(changes, change), needs, suppose(state, change), found, stuck);
                    } else {
                        stuck(stuck, new Literal.Positive(atom));
                    }
                } else if (!known) {
                    var answers = state.answers(atom);
                    for (var answer : answers) {
                        var extended = new HashMap<>(binding);
                        extended.putAll(answer);
                        explore(forWhat, rest, extended, changes, needs, state, found, stuck);
                    }
                    if (answers.isEmpty() && CREATED.contains(atom.relation()) && creatable(atom)) {
                        var made = NEW + atom.relation() + ":" + CREATIONS.incrementAndGet();
                        var values = new ArrayList<String>();
                        values.add(made);
                        for (var term : atom.terms().subList(1, atom.terms().size())) values.add(((Term.Constant) term).value());
                        var change = new Change(atom.relation(), values, null, forWhat);
                        var extended = new HashMap<>(binding);
                        extended.put(((Term.Variable) atom.terms().getFirst()).name(), made);
                        explore(forWhat, rest, extended, plus(changes, change), needs, suppose(state, change), found, stuck);
                    } else if (answers.isEmpty()) {
                        stuck(stuck, new Literal.Positive(atom));
                    }
                } else {
                    stuck(stuck, new Literal.Positive(atom));
                }
            }
            case Literal.Negative n -> {
                var atom = bound(n.atom(), binding);
                if (!state.holds(atom)) {
                    explore(forWhat, rest, binding, changes, needs, state, found, stuck);
                } else if (ground(atom) && (TOGGLED.contains(atom.relation()) || REMOVED.contains(atom.relation()))) {
                    var change = new Change(atom.relation(), null, values(atom), forWhat);
                    var verdict = allowed(change, changes);
                    if (verdict.ok()) {
                        change = change.breaking(verdict.promise());
                        explore(forWhat, rest, binding, plus(changes, change), needs, suppose(state, change), found, stuck);
                    } else {
                        stuck(stuck, new Literal.Negative(atom));
                    }
                } else {
                    stuck(stuck, new Literal.Negative(atom));
                }
            }
            case Literal.Comparison c -> {
                if (c.holds(binding)) explore(forWhat, rest, binding, changes, needs, state, found, stuck);
                else stuck(stuck, bound(c, binding));
            }
        }
    }

    // ---- making a broken rule hold

    /** The ways to make {@code violation} ({@code violates-<rule>(args)}) not hold, each mended of what it breaks. */
    Ways against(Atom violation) {
        var dead = new ArrayList<Dead>();
        var found = new ArrayList<Found>();
        for (var changes : unsupport(violation, List.of(), base, dead)) {
            found.addAll(mend(new Found(changes, List.of()), dead));
        }
        return new Ways(found, dead);
    }

    /**
     * The ways to take away every support of a ground {@code atom} (each way the changes it takes), starting from
     * {@code done}: one support at a time, each by one of what it stands on.
     */
    private List<List<Change>> unsupport(Atom atom, List<Change> done, Evaluation state, List<Dead> dead) {
        var supports = supports(atom, state);
        if (supports.isEmpty()) return List.of(List.of());
        var forWhat = atom.relation().startsWith("violates-") ? atom.relation().substring(9) : atom.relation();
        var support = supports.getFirst();
        var ways = new ArrayList<List<Change>>();
        for (var literal : support.clause().body()) {
            var options = new ArrayList<Change>();
            if (conditions.contains(literal)) continue; // a condition is never what is taken away
            switch (literal) {
                case Literal.Positive p -> {
                    var standing = bound(p.atom(), support.binding());
                    if (ground(standing) && (TOGGLED.contains(standing.relation()) || UNDONE_BY_CREATING.contains(standing.relation()))) {
                        options.add(new Change(standing.relation(), null, values(standing), forWhat));
                    }
                }
                case Literal.Negative n -> {
                    var absent = bound(n.atom(), support.binding());
                    if (ground(absent) && TOGGLED.contains(absent.relation())) {
                        options.add(new Change(absent.relation(), values(absent), null, forWhat));
                    }
                }
                case Literal.Comparison c -> {
                    // a comparison cannot be changed
                }
            }
            for (var option : options) {
                var all = plus(done, option);
                if (all.size() > MOST_CHANGES) continue;
                var verdict = allowed(option, done);
                if (!verdict.ok()) {
                    if (!verdict.why().startsWith(UNDO)) dead.add(new Dead(all, verdict.why()));
                    continue;
                }
                var change = option.breaking(verdict.promise());
                all = plus(done, change);
                for (var more : unsupport(atom, all, suppose(state, change), dead)) {
                    var way = new ArrayList<>(List.of(change));
                    way.addAll(more);
                    ways.add(way);
                }
            }
        }
        return ways;
    }

    /** {@code found}, and what it breaks mended in the same plan: every way that leaves no rule broken around what changed. */
    private List<Found> mend(Found found, List<Dead> dead) {
        var state = base;
        for (var change : found.changes()) state = suppose(state, change);
        var broken = broken(state);
        if (broken.isEmpty()) return List.of(found);
        var violation = broken.getFirst();
        var known = dead.size();
        var ways = unsupport(violation, found.changes(), state, dead);
        if (ways.isEmpty()) {
            if (dead.size() == known) dead.add(new Dead(found.changes(), "breaks " + shown.of(rule(violation)) + ", and nothing mends it"));
            return List.of();
        }
        var mended = new ArrayList<Found>();
        for (var more : ways) {
            var changes = new ArrayList<>(found.changes());
            changes.addAll(more);
            mended.addAll(mend(new Found(changes, found.needs()), dead));
        }
        return mended;
    }

    /**
     * The violations that hold now and did not before. Every rule is asked whole, not only around what changed: a
     * violation may reach a changed element through its body alone ({@code no-new-abstract(site, constructor)} through
     * the constructor's class). What held before is asked once.
     */
    private List<Atom> broken(Evaluation state) {
        var broken = new LinkedHashSet<Atom>();
        for (var relation : violations.entrySet()) {
            var terms = new ArrayList<Term>();
            for (var i = 0; i < relation.getValue(); i++) terms.add(Term.variable("V" + i));
            var query = new Atom(relation.getKey(), terms);
            var before = new java.util.HashSet<>(base.answers(query));
            for (var answer : state.answers(query)) {
                if (!before.contains(answer)) broken.add(bound(query, answer));
            }
        }
        return List.copyOf(broken);
    }

    /** What a ground atom stands on: each definition whose body holds for it, with the binding that makes it hold. */
    private List<Support> supports(Atom atom, Evaluation state) {
        var supports = new ArrayList<Support>();
        for (var clause : rules.defining(atom.relation())) {
            var binding = bind(clause.head(), values(atom));
            if (binding == null) continue;
            for (var answer : solutions(clause.body(), binding, state)) supports.add(new Support(clause, answer));
        }
        return supports;
    }

    private record Support(Clause clause, Map<String, String> binding) {
    }

    /** Every binding under which a body holds, read with the evaluation. */
    private static List<Map<String, String>> solutions(List<Literal> body, Map<String, String> binding, Evaluation state) {
        if (body.isEmpty()) return List.of(binding);
        var index = ready(body, binding);
        var literal = body.get(index);
        var rest = new ArrayList<>(body);
        rest.remove(index);
        var solutions = new ArrayList<Map<String, String>>();
        switch (literal) {
            case Literal.Positive p -> {
                for (var answer : state.answers(bound(p.atom(), binding))) {
                    var extended = new HashMap<>(binding);
                    extended.putAll(answer);
                    solutions.addAll(solutions(rest, extended, state));
                }
            }
            case Literal.Negative n -> {
                if (!state.holds(bound(n.atom(), binding))) solutions.addAll(solutions(rest, binding, state));
            }
            case Literal.Comparison c -> {
                if (c.holds(binding)) solutions.addAll(solutions(rest, binding, state));
            }
        }
        return solutions;
    }

    // ---- changes

    /**
     * Whether {@code change} may be made after {@code done}: not if it undoes one of them; one that breaks a promise
     * only as the first change, on purpose; any other may.
     */
    private Verdict allowed(Change change, List<Change> done) {
        for (var earlier : done) {
            if (!earlier.relation().equals(change.relation())) continue;
            if (change.removed() != null && change.removed().equals(earlier.added())
                    || change.added() != null && change.added().equals(earlier.removed())) {
                return new Verdict(false, null, UNDO + earlier.relation() + " of " + shown.of(change.element()));
            }
        }
        if (change.element().startsWith(NEW)) return new Verdict(true, null, null); // what is new promises nothing yet
        for (var kind : base.answers(Atom.of("kind", Term.constant(change.element()), Term.variable("K")))) {
            var why = promises.get(change.relation() + " of " + kind.get("K"));
            if (why == null) continue;
            var promise = "promise " + change.relation() + " of " + shown.of(change.element()) + ": " + why;
            return done.isEmpty() ? new Verdict(true, promise, null) : new Verdict(false, null, promise);
        }
        return new Verdict(true, null, null);
    }

    /** The change that makes a tuple of a set or toggled relation hold: it takes the place of the key's former value. */
    private static Change set(Atom atom, Evaluation state, String forWhat) {
        var values = values(atom);
        if (TOGGLED.contains(atom.relation()) || atom.relation().equals("before")) return new Change(atom.relation(), values, null, forWhat);
        var former = state.answers(new Atom(atom.relation(), List.of(atom.terms().getFirst(), Term.variable("Was"))));
        return new Change(atom.relation(), values, former.isEmpty() ? null : List.of(values.getFirst(), former.getFirst().get("Was")), forWhat);
    }

    /** A creation knows everything but the element it creates, the first argument. */
    private static boolean creatable(Atom atom) {
        return atom.terms().getFirst() instanceof Term.Variable v && !v.anonymous()
                && atom.terms().stream().skip(1).allMatch(t -> t instanceof Term.Constant);
    }

    /** The state after {@code change}; a creation brings what the created element is: its kind, its parent, its name. */
    private static Evaluation suppose(Evaluation state, Change change) {
        var added = new ArrayList<Tuple>();
        if (change.added() != null) added.add(new Tuple(change.relation(), change.added()));
        var values = change.added();
        if (values != null && values.getFirst().startsWith(NEW)) {
            var made = values.getFirst();
            switch (change.relation()) {
                case "member" -> {
                    added.add(new Tuple("kind", List.of(made, values.get(3))));
                    added.add(new Tuple("parent", List.of(made, values.get(1))));
                    added.add(new Tuple("name", List.of(made, values.get(2))));
                }
                case "parameter" -> {
                    added.add(new Tuple("kind", List.of(made, "param")));
                    added.add(new Tuple("parent", List.of(made, values.get(1))));
                    added.add(new Tuple("name", List.of(made, values.get(2))));
                }
                case "constructor", "constructor-for" -> {
                    added.add(new Tuple("kind", List.of(made, "constructor")));
                    added.add(new Tuple("parent", List.of(made, values.get(1))));
                    if (change.relation().equals("constructor-for")) added.add(new Tuple("constructor", List.of(made, values.get(1))));
                }
                default -> {
                    // nothing more is known of it
                }
            }
        }
        List<Tuple> removed = change.removed() == null ? List.of() : List.of(new Tuple(change.relation(), change.removed()));
        return state.suppose(added, removed);
    }

    /** Whether an atom speaks of an element a plan creates. */
    private static boolean created(Atom atom) {
        return atom.terms().stream().anyMatch(t -> t instanceof Term.Constant c && c.value().startsWith(NEW));
    }

    /** A clause with its variables renamed apart, to be read inside another body; its conditions stay conditions. */
    private Clause renamed(Clause clause) {
        var suffix = "~" + CREATIONS.incrementAndGet();
        java.util.function.Function<Atom, Atom> rename = atom -> new Atom(atom.relation(), atom.terms().stream()
                .map(t -> t instanceof Term.Variable v ? (Term) Term.variable(v.name() + suffix) : t).toList());
        var body = new ArrayList<Literal>();
        for (var literal : clause.body()) {
            Literal copy = switch (literal) {
                case Literal.Positive p -> new Literal.Positive(rename.apply(p.atom()));
                case Literal.Negative n -> new Literal.Negative(rename.apply(n.atom()));
                case Literal.Comparison c -> new Literal.Comparison(
                        c.left() instanceof Term.Variable v ? Term.variable(v.name() + suffix) : c.left(), c.equal(),
                        c.right() instanceof Term.Variable v ? Term.variable(v.name() + suffix) : c.right());
            };
            if (conditions.contains(literal)) conditions.add(copy);
            body.add(copy);
        }
        return new Clause(rename.apply(clause.head()), body);
    }

    // ---- terms

    /** The rule a violation is of, as people read it: {@code no-object(a.P#check(), a.P#gross())}. */
    private static Atom rule(Atom violation) {
        var relation = violation.relation();
        return new Atom(relation.startsWith("violates-") ? relation.substring(9) : relation, violation.terms());
    }

    private static boolean ground(Atom atom) {
        return atom.terms().stream().allMatch(t -> t instanceof Term.Constant);
    }

    private static List<String> values(Atom atom) {
        return atom.terms().stream().map(t -> ((Term.Constant) t).value()).toList();
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
