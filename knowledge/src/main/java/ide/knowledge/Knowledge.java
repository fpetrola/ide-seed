package ide.knowledge;

import ide.engine.Plan;
import ide.engine.QuickFix;
import ide.engine.Remedy;
import ide.engine.Trace;
import ide.engine.Violation;
import ide.program.ElementId;
import ide.program.Kind;
import ide.program.Primitive;
import ide.projection.Diagnostic;
import ide.projection.Projection;
import ide.refactoring.Args;
import ide.refactoring.Library;
import ide.refactoring.Run;
import ide.refactoring.Value;
import ide.refactoring.script.Script;
import ide.rules.Atom;
import ide.rules.Engine;
import ide.rules.Literal;
import ide.rules.Rules;
import ide.rules.Term;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Declarative knowledge: goals say what must hold for a violation or diagnostic to go away; definitions ({@code def},
 * Datalog, evaluated by {@code ide.rules}) and facts say when a predicate holds; effects say what makes a predicate
 * true, what they need, and how. Plans are deduced backward. A predicate that definitions say is made true by
 * changing what a definition looks at, through the primitive that changes it ({@link Abduction}); one an effect makes,
 * by the effect: its {@code for} candidates enumerated, those whose {@code when} holds kept. What a way needs either
 * holds already or is made first, the same way. A plan is at most four steps (eight levels of question and answer),
 * and a search answers the twelve cheapest.
 */
public final class Knowledge implements Remedy, QuickFix {

    private static final int MOST_STEPS = 4;
    private static final int MOST_PLANS = 12;

    /** A predicate with its arguments: variable names, or expressions where an effect says what it needs. */
    record Predicate(String name, List<String> args) {
    }

    record Goal(String code, Predicate needs) {
    }

    /** {@code holds} is true when {@code when} is, with its arguments bound. */
    record Fact(Predicate holds, String when) {
    }

    record Effect(String name, String title, Predicate makes, String when, String forVariable, String forItems,
                  List<Predicate> needs, List<Plan.Ask> asks, int cost, boolean keepsMeaning, Script body) {
    }

    /** A predicate a way needs to hold first, with its arguments' values. */
    private record Need(String name, List<Value> args) {
    }

    /** A step of a plan, ready to run: its title, its questions with their defaults, what it costs, and how it runs. */
    private record Step(String title, List<Plan.Ask> asks, int cost, boolean keepsMeaning,
                        BiConsumer<Projection, Map<String, String>> run) {
    }

    /** A way to make a predicate hold: steps in the order they run, what they cost, and the rank of the last. */
    private record Way(List<Step> steps, int cost, int rank) {

        Way then(List<Step> more, int moreCost) {
            var all = new ArrayList<>(steps);
            all.addAll(more);
            return new Way(all, cost + moreCost, rank);
        }
    }

    /** A search: the plans found, cheapest first, and how they were deduced, for {@code why}. */
    public record Search(Trace trace, List<Plan> plans) {
    }

    private final List<Goal> goals;
    private final List<Fact> facts;
    private final List<Effect> effects;
    private final Rules rules;
    private final Map<String, String> says;
    private final Library library;
    private final Engine engine = Engine.standard();

    Knowledge(List<Goal> goals, List<Fact> facts, List<Effect> effects, Rules rules, Map<String, String> says, Library library) {
        this.goals = List.copyOf(goals);
        this.facts = List.copyOf(facts);
        this.effects = List.copyOf(effects);
        this.rules = rules;
        this.says = Map.copyOf(says);
        this.library = library;
    }

    public static Knowledge parse(String text, Library library) {
        return KnowledgeParser.parse(text, library);
    }

    /** The knowledge the IDE ships with: how Java is ({@code java.rules}) and the rest ({@code knowledge.ref}). */
    public static Knowledge standard(Library library) {
        return parse(resource("java.rules") + "\n" + resource("knowledge.ref"), library);
    }

    private static String resource(String name) {
        try (var in = Knowledge.class.getResourceAsStream("/ide/knowledge/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<Plan> plans(Violation violation, Projection projection) {
        return search(violation, projection).plans();
    }

    @Override
    public List<Plan> plans(Diagnostic diagnostic, Projection projection) {
        return search(diagnostic, projection).plans();
    }

    /** The search for a violation: its site and target are elements, the rest of its facts text. */
    public Search search(Violation violation, Projection projection) {
        var facts = new HashMap<String, Object>(violation.facts());
        if (violation.site() != null) facts.put("site", violation.site());
        if (violation.target() != null) facts.put("target", violation.target());
        return search(violation.code(), facts, projection);
    }

    /**
     * The search for a diagnostic, with what it says: its symbol, where it is (path, line and column), and what the
     * compiler names in it (arguments).
     */
    public Search search(Diagnostic diagnostic, Projection projection) {
        return search(diagnostic.code(), Map.of("symbol", diagnostic.symbol(), "path", diagnostic.path(),
                "line", String.valueOf(diagnostic.line()), "column", String.valueOf(diagnostic.column()),
                "arguments", diagnostic.arguments()), projection);
    }

    /**
     * The search for a code with the facts that came with it (element ids, text): each goal a question, each way
     * tried an answer or dropped, and what an answer needs a question inside it.
     */
    public Search search(String code, Map<String, ?> given, Projection projection) {
        var known = new LinkedHashMap<String, Value>();
        given.forEach((name, fact) -> known.put(name, Value.of(fact)));
        var trace = new Trace();
        var ways = new ArrayList<Way>();
        for (var goal : goals) {
            if (!goal.code().equals(code)) continue;
            List<Value> args;
            try { // a goal's arguments are facts, or expressions over them
                var run = Run.on(projection, library);
                args = goal.needs().args().stream().map(arg -> known.containsKey(arg) ? known.get(arg) : Script.evaluate(arg, run, known)).toList();
            } catch (RuntimeException inapplicable) {
                trace.add(Trace.Kind.DROPPED, goal.needs().name() + ": " + inapplicable.getMessage());
                continue;
            }
            ways.addAll(ways(goal.needs().name(), args, known, projection, trace, 1));
        }
        ways.sort(Comparator.comparingInt(way -> way.cost() * 100 + way.rank()));
        return new Search(trace, ways.stream().limit(MOST_PLANS).map(this::plan).toList());
    }

    /** The ways to make {@code predicate(args)} hold, asked at {@code level}: by its definitions, then by its effects. */
    private List<Way> ways(String predicate, List<Value> args, Map<String, Value> known, Projection projection,
                           Trace trace, int level) {
        trace.open(Trace.Kind.QUESTION, predicate + "(" + String.join(", ", args.stream().map(a -> shown(a, projection)).toList()) + ")");
        var ways = new ArrayList<Way>();
        if (rules.defines(predicate)) ways.addAll(derived(predicate, args, known, projection, trace, level));
        for (var effect : effects) {
            if (!effect.makes().name().equals(predicate)) continue;
            var env = new LinkedHashMap<>(known);
            for (var i = 0; i < effect.makes().args().size(); i++) env.put(effect.makes().args().get(i), args.get(i));
            var run = Run.on(projection, library);
            try {
                if (effect.when() != null && !Value.truthy(Script.evaluate(effect.when(), run, env))) {
                    trace.add(Trace.Kind.DROPPED, effect.name() + ": " + effect.when());
                    continue;
                }
                var items = effect.forVariable() == null ? Collections.<Value>singletonList(null)
                        : Value.items(Script.evaluate(effect.forItems(), run, env));
                var rank = 0;
                for (var item : items) {
                    var variables = new LinkedHashMap<>(env);
                    if (effect.forVariable() != null) variables.put(effect.forVariable(), item);
                    var asks = effect.asks().stream().map(a -> new Plan.Ask(a.name(), a.question(),
                            Script.render(a.defaultAnswer(), run, variables))).toList();
                    var step = new Step(Script.render(effect.title(), run, variables), asks, effect.cost(), effect.keepsMeaning(),
                            (target, answers) -> run(effect, variables, asks, target, answers));
                    ways.addAll(after(effect.name(), needs(effect, variables, run), step, rank++, known, projection, trace, level));
                }
            } catch (RuntimeException inapplicable) { // e.g. the facts name elements that no longer exist
                trace.add(Trace.Kind.DROPPED, effect.name() + ": " + inapplicable.getMessage());
            }
        }
        trace.close();
        return ways;
    }

    private static List<Need> needs(Effect effect, Map<String, Value> variables, Run run) {
        return effect.needs().stream().map(need -> new Need(need.name(), need.args().stream()
                .map(a -> Script.evaluate(a, run, variables)).toList())).toList();
    }

    /**
     * The ways a predicate's definitions give: each found way of each definition is the changes it asks for, as steps
     * said as the knowledge says them ({@code say}), after what it needs made first; a definition that cannot hold is
     * dropped with the literal it could not get past.
     */
    private List<Way> derived(String predicate, List<Value> args, Map<String, Value> known, Projection projection,
                              Trace trace, int level) {
        var constants = new ArrayList<String>();
        for (var arg : args) {
            if (constant(arg) == null) return List.of();
            constants.add(constant(arg));
        }
        var abduction = new Abduction(rules, engine.evaluate(rules, new GraphFacts(projection.program(), known)));
        var ways = new ArrayList<Way>();
        var rank = 0;
        for (var attempt : abduction.attempts(predicate, constants)) {
            if (attempt.found().isEmpty()) {
                if (attempt.stuck() != null) trace.add(Trace.Kind.DROPPED, predicate + ": " + shown(attempt.stuck(), projection));
                continue;
            }
            for (var found : attempt.found()) {
                var steps = steps(predicate, found.changes(), projection);
                var needs = found.needs().stream().map(atom -> new Need(atom.relation(),
                        atom.terms().stream().map(t -> value(((Term.Constant) t).value(), projection)).toList())).toList();
                ways.addAll(after(predicate, needs, steps, rank++, known, projection, trace, level));
            }
        }
        return ways;
    }

    /** One step after the ways to make what it needs; see {@link #after(String, List, List, int, Map, Projection, Trace, int)}. */
    private List<Way> after(String name, List<Need> needs, Step step, int rank, Map<String, Value> known,
                            Projection projection, Trace trace, int level) {
        return after(name, needs, List.of(step), rank, known, projection, trace, level);
    }

    /**
     * The ways some steps give: alone if what they need holds; else after a way to make each need, within four steps.
     * The steps are an answer in the trace, with the questions of their needs inside it.
     */
    private List<Way> after(String name, List<Need> needs, List<Step> steps, int rank, Map<String, Value> known,
                            Projection projection, Trace trace, int level) {
        var below = new Trace();
        var ways = List.of(new Way(List.of(), 0, rank));
        var unmet = new ArrayList<String>();
        for (var need : needs) {
            var args = need.args();
            if (holds(need.name(), args, known, projection)) continue;
            unmet.add(need.name() + "(" + String.join(", ", args.stream().map(a -> shown(a, projection)).toList()) + ")");
            var made = level < MOST_STEPS ? ways(need.name(), args, known, projection, below, level + 1) : List.<Way>of();
            var next = new ArrayList<Way>();
            for (var way : ways) {
                for (var other : made) {
                    if (way.steps().size() + other.steps().size() + steps.size() <= MOST_STEPS) next.add(way.then(other.steps(), other.cost()));
                }
            }
            ways = next;
        }
        if (ways.isEmpty()) {
            trace.open(Trace.Kind.DROPPED, name + ": needs " + String.join(", ", unmet)).append(below).close();
            return List.of();
        }
        var cost = steps.stream().mapToInt(Step::cost).sum();
        if (steps.isEmpty()) trace.append(below);
        else trace.open(Trace.Kind.ANSWER, String.join(", then ", steps.stream().map(Step::title).toList())).append(below).close();
        return ways.stream().map(way -> way.then(steps, cost)).toList();
    }

    /** Whether {@code predicate(args)} holds: by its definitions, or by a fact; one neither speaks of must be made. */
    private boolean holds(String predicate, List<Value> args, Map<String, Value> known, Projection projection) {
        if (rules.defines(predicate)) {
            var terms = new ArrayList<Term>();
            for (var arg : args) {
                if (constant(arg) == null) return false;
                terms.add(Term.constant(constant(arg)));
            }
            return engine.evaluate(rules, new GraphFacts(projection.program(), known)).holds(new Atom(predicate, terms));
        }
        var run = Run.on(projection, library);
        for (var fact : facts) {
            if (!fact.holds().name().equals(predicate)) continue;
            var env = new LinkedHashMap<>(known);
            for (var i = 0; i < fact.holds().args().size(); i++) env.put(fact.holds().args().get(i), args.get(i));
            if (Value.truthy(Script.evaluate(fact.when(), run, env))) return true;
        }
        return false;
    }

    /**
     * The changes a definition asks for, as the primitives that make them, in the order they were found. A move and
     * a place before another member are one move; each step is said as {@code say <relation> for <predicate>} says.
     */
    private List<Step> steps(String predicate, List<Abduction.Change> changes, Projection projection) {
        var steps = new ArrayList<Step>();
        var moved = new HashMap<String, Abduction.Change>();
        for (var change : changes) if (change.relation().equals("parent")) moved.put(change.values().getFirst(), change);
        for (var change : changes) {
            var key = change.values().getFirst();
            var value = change.values().get(1);
            Primitive primitive = switch (change.relation()) {
                case "visibility" -> new Primitive.Set(new ElementId(key), "visibility", value);
                case "parent" -> new Primitive.Move(new ElementId(key), new ElementId(value), changes.stream()
                        .filter(c -> c.relation().equals("before") && c.values().getFirst().equals(key))
                        .map(c -> new ElementId(c.values().get(1))).findFirst().orElse(null));
                case "before" -> moved.containsKey(key) ? null
                        : new Primitive.Move(new ElementId(key), projection.program().element(new ElementId(key)).parent(), new ElementId(value));
                case "target" -> new Primitive.Retarget(key, new ElementId(value));
                case "constructor" -> new Primitive.Create(Kind.CONSTRUCTOR, new ElementId(value),
                        projection.program().element(new ElementId(value)).name());
                default -> throw new IllegalStateException("no primitive changes " + change.relation());
            };
            if (primitive == null) continue;
            var variables = new LinkedHashMap<String, Value>();
            variables.put("element", change.relation().equals("constructor") ? null : value(key, projection));
            variables.put("value", value(value, projection));
            variables.put("was", change.was() == null ? null : value(change.was().get(1), projection));
            var template = says.getOrDefault(change.relation() + " for " + predicate,
                    change.relation() + " ${element} ${was} -> ${value}");
            var title = Script.render(template, Run.on(projection, library), variables);
            steps.add(new Step(title, List.of(), 1, true, (target, answers) -> target.apply(primitive)));
        }
        return steps;
    }

    /** A way as a plan: its steps' titles in the order they run, their questions, and running them in that order. */
    private Plan plan(Way way) {
        var title = String.join(", then ", way.steps().stream().map(Step::title).toList());
        var asks = way.steps().stream().flatMap(step -> step.asks().stream()).toList();
        var changesMeaning = way.steps().stream().anyMatch(step -> !step.keepsMeaning());
        return new Plan(title, asks, changesMeaning, (projection, answers) -> way.steps().forEach(step -> step.run().accept(projection, answers)));
    }

    /** What a value is to the rules: an element or a use by its id, anything else as text; null if it has none. */
    private static String constant(Value value) {
        return switch (value) {
            case null -> null;
            case Value.Elem e -> e.id().value();
            case Value.Ref r -> r.id();
            case Value.Items items -> null;
            default -> Value.asText(value);
        };
    }

    /** A constant of the rules as a value: the element or the use it names, else the text. */
    private static Value value(String constant, Projection projection) {
        var program = projection.program();
        if (program.contains(new ElementId(constant))) return new Value.Elem(new ElementId(constant));
        if (program.unresolved(constant).isPresent()) return new Value.Ref(constant);
        if (program.references().stream().anyMatch(r -> r.id().equals(constant))) return new Value.Ref(constant);
        return new Value.Text(constant);
    }

    /** An element as people read it: by its address. */
    private static String shown(Value fact, Projection projection) {
        return fact instanceof Value.Elem e && projection.program().contains(e.id())
                ? projection.program().element(e.id()).qualified() : Value.asText(fact);
    }

    /** A literal as people read it: elements by their addresses, the rest as written. */
    private static String shown(Literal literal, Projection projection) {
        return switch (literal) {
            case Literal.Positive p -> shown(p.atom(), projection);
            case Literal.Negative n -> "not " + shown(n.atom(), projection);
            case Literal.Comparison c -> shown(c.left(), projection) + (c.equal() ? " = " : " != ") + shown(c.right(), projection);
        };
    }

    private static String shown(Atom atom, Projection projection) {
        return atom.relation() + "(" + String.join(", ", atom.terms().stream().map(t -> shown(t, projection)).toList()) + ")";
    }

    private static String shown(Term term, Projection projection) {
        return switch (term) {
            case Term.Variable v -> v.name();
            case Term.Constant c -> shown(value(c.value(), projection), projection);
        };
    }

    private void run(Effect effect, Map<String, Value> variables, List<Plan.Ask> asks, Projection projection, Map<String, String> answers) {
        var args = new HashMap<String, Object>();
        effect.body().params().forEach(p -> args.put(p.name(), variables.get(p.name())));
        asks.forEach(ask -> args.put(ask.name(), answers.getOrDefault(ask.name(), ask.defaultAnswer())));
        effect.body().apply(Run.on(projection, library), new Args(args));
    }
}
