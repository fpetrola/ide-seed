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
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;
import ide.refactoring.Args;
import ide.refactoring.Library;
import ide.refactoring.Planner;
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

/**
 * Declarative knowledge: goals say what must hold for a violation or diagnostic to go away; definitions ({@code def},
 * Datalog, evaluated by {@code ide.rules}) and facts say when a predicate holds; effects say what makes a predicate
 * true, what they need, and how. Plans are deduced backward. A predicate that definitions say is made true by
 * changing what a definition looks at, through the primitive that changes it ({@link Abduction}); one an effect makes,
 * by the effect: its {@code for} candidates enumerated, those whose {@code when} holds kept. What a way needs either
 * holds already or is made first, the same way. A plan is at most four steps (eight levels of question and answer),
 * and a search answers the twelve cheapest. It also plans intents: what one wants to hold is made to hold the same way,
 * with what that breaks mended in the same plan.
 */
public final class Knowledge implements Remedy, QuickFix, Planner {

    private static final int MOST_STEPS = 4;
    private static final int MOST_PLANS = 12;

    /** A predicate with its arguments: variable names, or expressions where an effect says what it needs. */
    record Predicate(String name, List<String> args) {
    }

    /** What a code needs: a predicate made to hold, or (for a problem that is a rule broken) a violation made not to. */
    record Goal(String code, Predicate needs, boolean holds) {
    }

    /** {@code holds} is true when {@code when} is, with its arguments bound. */
    record Fact(Predicate holds, String when) {
    }

    record Effect(String name, String title, Predicate makes, String when, String forVariable, String forItems,
                  List<Predicate> needs, List<Plan.Ask> asks, int cost, boolean keepsMeaning, Script body) {
    }

    /** A predicate a way needs to hold first, with its arguments' values. */
    private record Need(String name, List<Value> args, boolean holds) {

        Need(String name, List<Value> args) {
            this(name, args, true);
        }
    }

    /** A step of a plan, ready to run: its title, its questions with their defaults, what it costs, and how it runs. */
    private record Step(String title, List<Plan.Ask> asks, int cost, boolean keepsMeaning, Runner run) {
    }

    /** How a step runs: on a projection, with the answers given, and the elements the plan created so far by placeholder. */
    @FunctionalInterface
    private interface Runner {
        void run(Projection projection, Map<String, String> answers, Map<String, ElementId> created);
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
    private final java.util.Set<Literal> conditions;
    private final Map<String, String> says;
    private final Map<String, Abduction.Promise> promises;
    private final Map<String, List<String>> questions;
    private final Library library;
    private final Engine engine = Engine.standard();

    Knowledge(List<Goal> goals, List<Fact> facts, List<Effect> effects, Rules rules, java.util.Set<Literal> conditions,
              Map<String, String> says, Map<String, Abduction.Promise> promises, Map<String, List<String>> questions, Library library) {
        this.goals = List.copyOf(goals);
        this.facts = List.copyOf(facts);
        this.effects = List.copyOf(effects);
        this.rules = rules;
        this.conditions = conditions;
        this.says = Map.copyOf(says);
        this.promises = Map.copyOf(promises);
        this.questions = Map.copyOf(questions);
        this.library = library;
    }

    public static Knowledge parse(String text, Library library) {
        return KnowledgeParser.parse(text, library);
    }

    /**
     * The knowledge the IDE ships with: how Java is ({@code java.rules}) and the rest ({@code knowledge.ref}). It plans
     * the library's intents.
     */
    public static Knowledge standard(Library library) {
        var knowledge = parse(resource("java.rules") + "\n" + resource("knowledge.ref"), library);
        library.planner(knowledge);
        return knowledge;
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
            ways.addAll(ways(goal.needs().name(), args, goal.holds(), known, projection, trace, 1));
        }
        ways.sort(Comparator.comparingInt(way -> way.cost() * 100 + way.rank()));
        return new Search(trace, ways.stream().limit(MOST_PLANS).map(this::plan).toList());
    }

    /**
     * Makes an intent's want hold in a run: what it asks for, and what that breaks mended, as one plan: the cheapest
     * that keeps meaning and asks nothing. Its own changes count for nothing against the most a plan may change. When
     * no way is mended, the want alone: what the rules did not foresee is the repairer's, which judges every plan. A
     * want that holds already changes nothing; one that nothing makes hold is refused with the first literal it could
     * not get past. How it was deduced goes with the run's search.
     */
    @Override
    public void want(String intent, String want, Map<String, Value> variables, Run run) {
        var projection = run.projection();
        var wanted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Literal, Boolean>());
        wanted.addAll(conditions);
        var binding = new HashMap<String, String>();
        var lists = new HashMap<String, List<String>>();
        variables.forEach((name, value) -> {
            if (value instanceof Value.Items items) lists.put(name, items.items().stream().map(Knowledge::constant).toList());
            else if (constant(value) != null) binding.put(name, constant(value));
        });
        var body = KnowledgeParser.want(want, lists, wanted);
        var trace = new Trace();
        trace.open(Trace.Kind.QUESTION, intent + "(" + String.join(", ", variables.values().stream().map(v -> shown(v, projection)).toList()) + ")");
        var found = abduction(variables, projection, wanted).wanted(intent, body, binding);
        if (found.holds()) {
            run.searched(trace.add(Trace.Kind.NOTE, "it holds already").close());
            return;
        }
        if (found.unmended().isEmpty()) {
            run.searched(trace.close());
            throw new PrimitiveRejected("intent.unreachable", intent + " needs " + shown(found.stuck(), projection));
        }
        dead(found.dead(), projection, trace, intent);
        var ways = new ArrayList<Way>();
        var rank = 0;
        for (var way : found.found()) {
            ways.addAll(after(intent, needs(way, projection), steps(way.changes(), projection, intent), rank++, variables, projection, trace, 1));
        }
        ways.sort(Comparator.comparingInt(way -> way.cost() * 100 + way.rank()));
        var plan = ways.stream().map(this::plan).filter(p -> !p.changesMeaning() && p.asks().isEmpty()).findFirst()
                .orElse(null);
        if (plan == null) { // what it asks for, with what it needs, and nothing mended: the repairer's
            var alone = new ArrayList<Way>();
            for (var way : found.unmended()) {
                alone.addAll(after(intent, needs(way, projection), steps(way.changes(), projection, intent), 0, variables, projection, new Trace(), 1));
            }
            alone.sort(Comparator.comparingInt(Way::cost));
            if (alone.isEmpty()) {
                run.searched(trace.close());
                var need = found.unmended().getFirst().needs().getFirst();
                throw new PrimitiveRejected("intent.unreachable", intent + " needs " + (need.holds() ? "" : "not ") + shown(need.atom(), projection));
            }
            plan = plan(alone.getFirst());
            trace.add(Trace.Kind.NOTE, "no way the rules mend keeps meaning: " + plan.title() + " alone, which the repairer judges");
        }
        run.searched(trace.close());
        plan.apply().accept(projection, Map.of());
    }

    /**
     * The ways to make {@code predicate(args)} hold, asked at {@code level}: by its definitions, then by its effects.
     * One that must not hold ({@code holds} false) is a broken rule, made to hold by its definitions alone.
     */
    private List<Way> ways(String predicate, List<Value> args, boolean holds, Map<String, Value> known, Projection projection,
                           Trace trace, int level) {
        var name = holds ? predicate : predicate.replaceFirst("^violates-", "");
        trace.open(Trace.Kind.QUESTION, name + "(" + String.join(", ", args.stream().map(a -> shown(a, projection)).toList()) + ")");
        var ways = new ArrayList<Way>();
        if (!holds) {
            if (rules.defines(predicate)) ways.addAll(against(predicate, args, known, projection, trace, level));
            trace.close();
            return ways;
        }
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
                            (target, answers, made) -> run(effect, variables, asks, target, answers));
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
        var abduction = abduction(known, projection);
        var ways = new ArrayList<Way>();
        var rank = 0;
        for (var attempt : abduction.attempts(predicate, constants)) {
            dead(attempt.dead(), projection, trace);
            if (attempt.found().isEmpty()) {
                if (attempt.stuck() != null && attempt.dead().isEmpty()) trace.add(Trace.Kind.DROPPED, predicate + ": " + shown(attempt.stuck(), projection));
                continue;
            }
            for (var found : attempt.found()) ways.addAll(after(predicate, needs(found, projection), steps(found.changes(), projection), rank++, known, projection, trace, level));
        }
        return ways;
    }

    /**
     * The ways to make a broken rule hold where it is broken: each takes away one of what the broken instance stands
     * on, and mends what that breaks; the ways given up are in the trace, with why.
     */
    private List<Way> against(String violation, List<Value> args, Map<String, Value> known, Projection projection,
                              Trace trace, int level) {
        var terms = new ArrayList<Term>();
        for (var arg : args) {
            if (constant(arg) == null) return List.of();
            terms.add(Term.constant(constant(arg)));
        }
        var found = abduction(known, projection).against(new Atom(violation, terms));
        dead(found.dead(), projection, trace);
        var ways = new ArrayList<Way>();
        var rank = 0;
        for (var way : found.found()) ways.addAll(after(violation, needs(way, projection), steps(way.changes(), projection), rank++, known, projection, trace, level));
        return ways;
    }

    private Abduction abduction(Map<String, Value> known, Projection projection) {
        return abduction(known, projection, conditions);
    }

    private Abduction abduction(Map<String, Value> known, Projection projection, java.util.Set<Literal> conditions) {
        return new Abduction(rules, conditions, engine.evaluate(rules, new GraphFacts(projection, known)), promises, new Abduction.Shown() {
            @Override
            public String of(Atom atom) {
                return shown(atom, projection);
            }

            @Override
            public String of(String constant) {
                return shown(value(constant, projection), projection);
            }
        });
    }

    private static List<Need> needs(Abduction.Found found, Projection projection) {
        return found.needs().stream().map(need -> new Need(need.atom().relation(), need.atom().terms().stream()
                .map(t -> value(((Term.Constant) t).value(), projection)).toList(), need.holds())).toList();
    }

    /** The ways given up, as their steps would have been said, and why. */
    private void dead(List<Abduction.Dead> dead, Projection projection, Trace trace) {
        dead(dead, projection, trace, null);
    }

    /** As {@link #dead(List, Projection, Trace)}, what an intent wanted said as the intent. */
    private void dead(List<Abduction.Dead> dead, Projection projection, Trace trace, String wanted) {
        for (var way : dead) {
            trace.add(Trace.Kind.DROPPED, said(steps(way.changes(), projection, wanted)) + ": " + way.why());
        }
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
            if (holds(need.name(), args, known, projection) == need.holds()) continue;
            unmet.add((need.holds() ? "" : "not ") + need.name() + "(" + String.join(", ", args.stream().map(a -> shown(a, projection)).toList()) + ")");
            var made = level < MOST_STEPS ? ways(need.name(), args, need.holds(), known, projection, below, level + 1) : List.<Way>of();
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
        else trace.open(Trace.Kind.ANSWER, said(steps)).append(below).close();
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
            return engine.evaluate(rules, new GraphFacts(projection, known)).holds(new Atom(predicate, terms));
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
     * The changes a way asks for, as the primitives that make them, in the order they were found. A move and a place
     * before another member are one move; a toggled relation is set to true or false; an element created and what is
     * set of it are one step, a method created answering what its type starts with. Each step is said as
     * {@code say [not] <relation> for <what it was done for>} says, and what {@code ask} declares of an element created
     * is asked, with what the rules deduced as the answer proposed.
     */
    private List<Step> steps(List<Abduction.Change> changes, Projection projection) {
        return steps(changes, projection, null);
    }

    /** As {@link #steps(List, Projection)}; a change an intent's want asks for is said as the intent, if nothing says it. */
    private List<Step> steps(List<Abduction.Change> changes, Projection projection, String wanted) {
        var steps = new ArrayList<Step>();
        var moved = new HashMap<String, Abduction.Change>();
        for (var change : changes) if (change.relation().equals("parent")) moved.put(change.element(), change);
        var groups = new LinkedHashMap<String, List<Abduction.Change>>();
        for (var change : changes) {
            var key = change.element();
            if (change.added() != null && key.startsWith(Abduction.NEW) && Abduction.CREATED.contains(change.relation())) {
                groups.put(key, new ArrayList<>(List.of(change)));
            } else if (groups.containsKey(key)) {
                groups.get(key).add(change);
            }
        }
        var asked = new java.util.HashSet<String>();
        var implemented = new java.util.HashSet<String>();
        var creations = new HashMap<String, Abduction.Change>();
        groups.forEach((made, group) -> creations.put(made, group.getFirst()));
        for (var change : changes) {
            var key = change.element();
            if (groups.containsKey(key)) {
                if (groups.get(key).getFirst() == change) steps.add(created(groups.get(key), creations, projection, asked, wanted));
                continue;
            }
            var on = change.added() != null;
            var value = on && change.added().size() > 1 ? change.added().get(1) : null;
            if (change.relation().equals("unimplemented")) { // every method a class implements, one step
                if (implemented.add(key)) {
                    var methods = changes.stream().filter(c -> c.relation().equals("unimplemented") && c.element().equals(key))
                            .map(c -> c.removed().get(1)).toList();
                    steps.add(implementing(key, methods, change.forWhat(), projection));
                }
                continue;
            }
            if (change.relation().equals("initialized")) { // a value where the field is declared: the person's
                steps.add(initializing(key, change.forWhat(), projection, asked));
                continue;
            }
            var placed = changes.stream().filter(c -> c.relation().equals("before") && c.element().equals(key))
                    .map(c -> c.added().get(1)).findFirst().orElse(null);
            // made when it runs: what it names may be what the plan created before it
            java.util.function.Function<Map<String, ElementId>, Primitive> primitive = switch (change.relation()) {
                case "static", "final", "abstract" -> made -> new Primitive.Set(resolved(key, made), change.relation(), String.valueOf(on));
                case "type", "visibility" -> made -> new Primitive.Set(resolved(key, made), change.relation(), value);
                case "parent" -> made -> new Primitive.Move(resolved(key, made), resolved(value, made), placed == null ? null : resolved(placed, made));
                case "before" -> moved.containsKey(key) ? null
                        : made -> new Primitive.Move(resolved(key, made), projection.program().element(new ElementId(key)).parent(), resolved(value, made));
                case "superclass" -> made -> new Primitive.Link(resolved(key, made), ide.program.Mode.EXTENDS, resolved(value, made));
                case "target" -> made -> new Primitive.Retarget(key, resolved(value, made));
                case "exists" -> made -> new Primitive.Delete(resolved(key, made)); // what is not there any more is deleted
                default -> throw new IllegalStateException("no primitive changes " + change.relation());
            };
            if (primitive == null) continue;
            var variables = new LinkedHashMap<String, Value>();
            variables.put("element", value(key, projection));
            variables.put("called", new Value.Text(called(key, projection)));
            variables.put("value", value == null ? null : named(value, creations, projection));
            variables.put("was", change.removed() == null || change.removed().size() < 2 ? null : value(change.removed().get(1), projection));
            var title = title((on ? "" : "not ") + change.relation(), change.forWhat(), variables, projection, wanted);
            var promised = change.promise() != null; // broken on purpose: it changes meaning, and costs more
            steps.add(new Step(title, List.of(), promised ? 2 : 1, !promised, (target, answers, made) -> target.apply(primitive.apply(made))));
        }
        return steps;
    }

    /** A value a change gives as a title says it: an element the plan creates, by the name it will have. */
    private static Value named(String value, Map<String, Abduction.Change> creations, Projection projection) {
        var creation = creations.get(value);
        if (creation == null) return value(value, projection);
        return new Value.Text(switch (creation.relation()) {
            case "member", "parameter" -> creation.added().get(2);
            default -> projection.program().element(new ElementId(creation.added().get(1))).name();
        });
    }

    /**
     * An element created, with what the way sets of it, as one step: the creation, each change in turn (an asked one
     * with the answer given, or the one proposed), a parameter for each argument a use passes, and, for a method, the
     * body that answers what its type starts with.
     */
    private Step created(List<Abduction.Change> group, Map<String, Abduction.Change> creations, Projection projection,
                         java.util.Set<String> asked, String wanted) {
        var creation = group.getFirst();
        var values = creation.added();
        var made = values.getFirst();
        var owner = new ElementId(values.get(1));
        var kind = switch (creation.relation()) {
            case "member" -> values.get(3);
            case "parameter" -> "param";
            default -> "constructor";
        };
        var named = switch (creation.relation()) {
            case "member", "parameter" -> values.get(2);
            default -> projection.program().element(owner).name();
        };
        var asks = new ArrayList<Plan.Ask>();
        var sets = new ArrayList<Setting>();
        for (var change : group.subList(1, group.size())) {
            var on = change.added() != null;
            var value = on && change.added().size() > 1 ? change.added().get(1) : null;
            switch (change.relation()) {
                case "takes-arguments" -> {
                    var arguments = projection.needs(change.added().get(1)).map(n -> n.arguments()).orElse(List.of());
                    sets.add((target, answers, id, done) -> {
                        var made2 = new ArrayList<Primitive>();
                        for (var argument : arguments) made2.add(new Primitive.Create(Kind.PARAM, id, argument.name(), Map.of(ide.program.Fact.TYPE, argument.type()), null));
                        return made2;
                    });
                }
                case "static", "final", "abstract" -> sets.add((target, answers, id, done) -> List.of(new Primitive.Set(id, change.relation(), String.valueOf(on))));
                case "stores" -> { // a statement of its callable gives the field the parameter's value
                    var callable = change.added().get(1);
                    var field = change.added().get(2);
                    sets.add((target, answers, id, done) -> List.of(new Primitive.Create(Kind.STATEMENT, resolved(callable, done), "",
                            Map.of(ide.program.Fact.BODY, ide.program.Body.name(resolved(field, done)) + " = " + ide.program.Body.name(id)), null)));
                }
                case "same-superclass" -> { // it extends what the class it is like extends, as that class writes it
                    var like = change.added().get(1);
                    sets.add((target, answers, id, done) -> {
                        var written = target.program().element(resolved(like, done)).fact(ide.program.Fact.SUPERCLASS);
                        return written == null ? List.<Primitive>of() : List.<Primitive>of(new Primitive.Set(id, "superclass", written));
                    });
                }
                case "same-parameters" -> { // a parameter for each of the method it is like, of the same name and type
                    var like = change.added().get(1);
                    sets.add((target, answers, id, done) -> target.program().children(resolved(like, done)).stream()
                            .filter(e -> e.kind() == Kind.PARAM)
                            .<Primitive>map(p -> new Primitive.Create(Kind.PARAM, id, p.name(), Map.of(ide.program.Fact.TYPE, p.fact(ide.program.Fact.TYPE)), null))
                            .toList());
                }
                case "passes" -> { // the caller hands the parameter's constructor what it gave the field, first, and gives it no more
                    var caller = change.added().get(1);
                    var field = change.added().get(2);
                    var constructor = values.get(1);
                    sets.add((target, answers, id, done) -> passing(target, resolved(caller, done), resolved(field, done), resolved(constructor, done)));
                }
                default -> {
                    var question = questions.get(change.relation() + " of new " + kind);
                    if (question != null) {
                        var name = question.getFirst();
                        while (!asked.add(name)) name = name + "'";
                        var answer = name;
                        asks.add(new Plan.Ask(answer, question.get(1), value));
                        sets.add((target, answers, id, done) -> List.of(new Primitive.Set(id, change.relation(), answers.getOrDefault(answer, value))));
                    } else {
                        sets.add((target, answers, id, done) -> List.of(new Primitive.Set(id, change.relation(), value)));
                    }
                }
            }
        }
        var variables = new LinkedHashMap<String, Value>();
        variables.put("kind", new Value.Text(kind));
        variables.put("named", new Value.Text(named));
        variables.put("owner", named(owner.value(), creations, projection));
        variables.put("value", named(owner.value(), creations, projection));
        variables.put("type", named(typeOf(values.get(1), creations, projection), creations, projection));
        var title = title(creation.relation(), creation.forWhat(), variables, projection, wanted);
        var method = kind.equals("method");
        var passed = group.stream().anyMatch(change -> change.relation().equals("passes"));
        var keeps = !creation.relation().equals("parameter") || passed && uncalled(values.get(1), creations, projection);
        return new Step(title, List.copyOf(asks), 2, keeps, (target, answers, done) -> {
            var in = resolved(values.get(1), done); // what it is created in may have been created by the plan
            var primitive = switch (creation.relation()) {
                case "member" -> new Primitive.Create(Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)), in, named);
                case "parameter" -> new Primitive.Create(Kind.PARAM, in, named, Map.of(), values.get(3));
                default -> new Primitive.Create(Kind.CONSTRUCTOR, in, named);
            };
            var id = target.apply(primitive).produced();
            done.put(made, id);
            for (var set : sets) set.of(target, answers, id, done).forEach(target::apply);
            if (method) answeringItsDefault(target, id);
        });
    }

    /** What is set of an element created, once it is: on the projection, with the answers given and what the plan created so far. */
    @FunctionalInterface
    private interface Setting {
        List<Primitive> of(Projection target, Map<String, String> answers, ElementId id, Map<String, ElementId> created);
    }

    /**
     * The type an element is in, through what the plan creates: a type created is its own, a constructor created is in
     * its type, a member in its owner; an element of the program, a type (or a package) itself, or the type it is in.
     */
    private static String typeOf(String element, Map<String, Abduction.Change> creations, Projection projection) {
        var creation = creations.get(element);
        if (creation != null && creation.relation().equals("member")
                && Kind.valueOf(creation.added().get(3).toUpperCase(java.util.Locale.ROOT)).isType()) return element; // a type created
        if (creation != null) return typeOf(creation.added().get(1), creations, projection);
        var program = projection.program();
        var id = new ElementId(element);
        var inType = program.contains(id) && !program.element(id).kind().isType() && program.element(id).kind() != Kind.PACKAGE;
        return inType ? program.enclosingType(id).id().value() : element; // a package is where it is
    }

    /**
     * Whether nothing could be calling a callable the value a new parameter of it is made up for: an existing one no
     * call names, or a constructor the plan creates in a type nothing uses as a type (only to extend it, say). A call
     * the graph does not see, an implicit super(), stops compiling instead, which the verifier sees.
     */
    private static boolean uncalled(String callable, Map<String, Abduction.Change> creations, Projection projection) {
        var program = projection.program();
        if (creations.containsKey(callable)) {
            var type = new ElementId(typeOf(callable, creations, projection));
            return program.referencesTo(type).stream().allMatch(r -> r.mode().isLink() && r.mode() != ide.program.Mode.DECLARED);
        }
        return program.referencesTo(new ElementId(callable)).stream()
                .noneMatch(r -> r.mode() == ide.program.Mode.CALL || r.mode() == ide.program.Mode.NEW);
    }

    /**
     * A constructor calls another first, giving it what it gave a field, and gives the field nothing more: the
     * statement that gave it goes, and so does a call to a constructor with nothing, which the new one takes the place of.
     */
    private static List<Primitive> passing(Projection target, ElementId caller, ElementId field, ElementId constructor) {
        var program = target.program();
        var primitives = new ArrayList<Primitive>();
        String value = null;
        ElementId giving = null;
        for (var statement : program.children(caller).stream().filter(e -> e.kind() == Kind.STATEMENT).toList()) {
            var said = target.said(statement.id()).map(text -> ide.program.Body.parse(text, program, caller).statements()).orElse(List.of());
            if (said.size() != 1) continue;
            if (said.getFirst() instanceof ide.program.Body.Construct made && made.arguments().isEmpty()) primitives.add(new Primitive.Delete(statement.id()));
            if (said.getFirst() instanceof ide.program.Body.Assign given && given.field().equals(field) && giving == null) {
                giving = statement.id();
                value = ide.program.Body.text(given.value());
            }
        }
        if (giving == null) throw new IllegalStateException(program.element(caller).qualified() + " gives " + program.element(field).name() + " nothing it can say");
        primitives.add(new Primitive.Create(Kind.STATEMENT, caller, "", Map.of(ide.program.Fact.BODY, ide.program.Body.name(constructor) + "(" + value + ")"), null));
        primitives.add(new Primitive.Delete(giving));
        return primitives;
    }

    /** An element a way names: one the plan created, by its placeholder, or one of the program. */
    private static ElementId resolved(String element, Map<String, ElementId> created) {
        return created.getOrDefault(element, new ElementId(element));
    }

    /** Steps as a plan says them: their titles in order, one said again right after itself said once. */
    private static String said(List<Step> steps) {
        var titles = new ArrayList<String>();
        for (var step : steps) if (titles.isEmpty() || !titles.getLast().equals(step.title())) titles.add(step.title());
        return String.join(", then ", titles);
    }

    /**
     * The methods a class inherits without a body, each implemented in it as declared where it was inherited from: its
     * visibility, its type, its parameters; answering what its type starts with until it is written.
     */
    private Step implementing(String type, List<String> methods, String forWhat, Projection projection) {
        var program = projection.program();
        var owner = new ElementId(type);
        var shapes = methods.stream().map(id -> program.element(new ElementId(id))).toList();
        var variables = new LinkedHashMap<String, Value>();
        variables.put("element", value(type, projection));
        variables.put("called", new Value.Text(called(type, projection)));
        var title = title("not unimplemented", forWhat, variables, projection, null);
        return new Step(title, List.of(), 1, true, (target, answers, done) -> {
            for (var shape : shapes) {
                var id = target.apply(new Primitive.Create(Kind.METHOD, owner, shape.name())).produced();
                target.apply(new Primitive.Set(id, "visibility", shape.fact(ide.program.Fact.VISIBILITY)));
                target.apply(new Primitive.Set(id, "type", shape.fact(ide.program.Fact.TYPE)));
                for (var param : program.children(shape.id()).stream().filter(e -> e.kind() == Kind.PARAM).toList()) {
                    target.apply(new Primitive.Create(Kind.PARAM, id, param.name(), Map.of(ide.program.Fact.TYPE, param.fact(ide.program.Fact.TYPE)), null));
                }
                answeringItsDefault(target, id);
            }
        });
    }

    /**
     * A method made answers what its type starts with until it is written; one that answers nothing, or an abstract
     * one, which has no body, says nothing.
     */
    private static void answeringItsDefault(Projection target, ElementId method) {
        var made = target.program().element(method);
        if ("void".equals(made.fact(ide.program.Fact.TYPE)) || "true".equals(made.fact(ide.program.Fact.ABSTRACT))) return;
        target.apply(new Primitive.Create(Kind.STATEMENT, method, "", Map.of(ide.program.Fact.BODY, "return default"), null));
    }

    /** A value given where a field is declared: asked, with what the knowledge proposes ({@code ask ... default}). */
    private Step initializing(String field, String forWhat, Projection projection, java.util.Set<String> asked) {
        var element = projection.program().element(new ElementId(field));
        var question = questions.get("initialized of " + element.kind().name().toLowerCase(java.util.Locale.ROOT));
        var proposed = "";
        if (question != null && question.size() > 2) {
            var answers = engine.evaluate(rules, new GraphFacts(projection, Map.of()))
                    .answers(Atom.of(question.get(2), Term.constant(field), Term.variable("V")));
            if (!answers.isEmpty()) proposed = answers.getFirst().get("V");
        }
        var name = question == null ? "value" : question.getFirst();
        while (!asked.add(name)) name = name + "'";
        var answer = name;
        var value = proposed;
        var variables = new LinkedHashMap<String, Value>();
        variables.put("element", value(field, projection));
        variables.put("called", new Value.Text(called(field, projection)));
        var title = title("initialized", forWhat, variables, projection, null);
        var asks = List.of(new Plan.Ask(answer, question == null ? "initial value" : question.get(1), value));
        return new Step(title, asks, 1, true, (target, given, done) ->
                target.apply(new Primitive.Set(new ElementId(field), "initializer", given.getOrDefault(answer, value))));
    }

    /**
     * A step's title, as {@code say <relation> for <what>} says it; with nothing said, the intent whose want asked for it
     * ({@code wanted}), or the change as it is.
     */
    private String title(String relation, String forWhat, Map<String, Value> variables, Projection projection, String wanted) {
        var said = says.get(relation + " for " + forWhat);
        if (said == null && forWhat.equals(wanted)) return wanted;
        if (said != null) {
            try {
                return Script.render(said, Run.on(projection, library), variables);
            } catch (RuntimeException unsaid) {
                // what it names is not there yet: the change as it is
            }
        }
        var what = variables.containsKey("element") ? variables.get("element") : variables.get("named");
        var was = variables.get("was");
        var value = variables.get("value");
        return relation + " " + shown(what, projection) + (was == null ? "" : " " + shown(was, projection))
                + (value == null ? "" : " -> " + shown(value, projection));
    }

    /** A way as a plan: its steps' titles in the order they run, their questions, and running them in that order. */
    private Plan plan(Way way) {
        var title = said(way.steps());
        var asks = way.steps().stream().flatMap(step -> step.asks().stream()).toList();
        var changesMeaning = way.steps().stream().anyMatch(step -> !step.keepsMeaning());
        return new Plan(title, asks, changesMeaning, (projection, answers) -> {
            var created = new HashMap<String, ElementId>();
            way.steps().forEach(step -> step.run().run(projection, answers, created));
        });
    }

    /** How an element is called in a title: a method or a constructor with {@code ()}, anything else by its name. */
    private static String called(String id, Projection projection) {
        if (!projection.program().contains(new ElementId(id))) return id;
        var element = projection.program().element(new ElementId(id));
        return element.kind() == Kind.METHOD || element.kind() == Kind.CONSTRUCTOR ? element.name() + "()" : element.name();
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

    /** An element as people read it: by its address; a list, each of its items. */
    private static String shown(Value fact, Projection projection) {
        if (fact instanceof Value.Items items) return "[" + String.join(", ", items.items().stream().map(item -> shown(item, projection)).toList()) + "]";
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
            case Term.Variable v -> v.anonymous() ? "_" : v.name();
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
