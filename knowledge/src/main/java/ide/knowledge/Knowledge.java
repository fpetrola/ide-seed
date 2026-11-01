package ide.knowledge;

import ide.engine.Plan;
import ide.engine.QuickFix;
import ide.engine.Remedy;
import ide.engine.Trace;
import ide.engine.Violation;
import ide.projection.Diagnostic;
import ide.projection.Projection;
import ide.refactoring.Args;
import ide.refactoring.Library;
import ide.refactoring.Run;
import ide.refactoring.Value;
import ide.refactoring.script.Script;

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
 * Declarative knowledge: goals say what must hold for a violation or diagnostic to go away, facts say when a
 * predicate holds, effects say what makes a predicate true, what they need, and how. Plans are deduced backward: a
 * goal's predicate is matched to the effects that make it, binding the goal's arguments to the effect's variables
 * by position, enumerating {@code for} candidates and keeping those whose {@code when} holds; what a candidate needs
 * either holds already or is made first, the same way. A plan is at most four effects (eight levels of question and
 * answer), and a search answers the twelve cheapest.
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
                  List<Predicate> needs, List<Plan.Ask> asks, int cost, Script body) {
    }

    /** An effect ready to run: its variables bound, its questions with their defaults, its title. */
    private record Step(Effect effect, Map<String, Value> variables, List<Plan.Ask> asks, String title) {
    }

    /** A way to make a predicate hold: effects in the order they run, what they cost, and the rank of the last. */
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
    private final Library library;

    Knowledge(List<Goal> goals, List<Fact> facts, List<Effect> effects, Library library) {
        this.goals = List.copyOf(goals);
        this.facts = List.copyOf(facts);
        this.effects = List.copyOf(effects);
        this.library = library;
    }

    public static Knowledge parse(String text, Library library) {
        return KnowledgeParser.parse(text, library);
    }

    /** The knowledge the IDE ships with ({@code knowledge.ref}), acting through {@code library}. */
    public static Knowledge standard(Library library) {
        try (var in = Knowledge.class.getResourceAsStream("/ide/knowledge/knowledge.ref")) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), library);
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

    /** The search for a diagnostic, with what it says: its symbol, and where it is (path, line and column). */
    public Search search(Diagnostic diagnostic, Projection projection) {
        return search(diagnostic.code(), Map.of("symbol", diagnostic.symbol(), "path", diagnostic.path(),
                "line", String.valueOf(diagnostic.line()), "column", String.valueOf(diagnostic.column())), projection);
    }

    /**
     * The search for a code with the facts that came with it (element ids, text): each goal a question, each effect
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

    /** The ways to make {@code predicate(args)} hold, asked at {@code level}: one effect each, after what it needs. */
    private List<Way> ways(String predicate, List<Value> args, Map<String, Value> known, Projection projection,
                           Trace trace, int level) {
        trace.open(Trace.Kind.QUESTION, predicate + "(" + String.join(", ", args.stream().map(a -> shown(a, projection)).toList()) + ")");
        var ways = new ArrayList<Way>();
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
                    var step = new Step(effect, variables, asks, Script.render(effect.title(), run, variables));
                    ways.addAll(after(step, rank++, known, projection, trace, level));
                }
            } catch (RuntimeException inapplicable) { // e.g. the facts name elements that no longer exist
                trace.add(Trace.Kind.DROPPED, effect.name() + ": " + inapplicable.getMessage());
            }
        }
        trace.close();
        return ways;
    }

    /** The ways a step gives: alone if what it needs holds; else after a way to make each need, within four steps. */
    private List<Way> after(Step step, int rank, Map<String, Value> known, Projection projection, Trace trace, int level) {
        var run = Run.on(projection, library);
        var below = new Trace();
        var ways = List.of(new Way(List.of(), 0, rank));
        var unmet = new ArrayList<String>();
        for (var need : step.effect().needs()) {
            var args = need.args().stream().map(a -> Script.evaluate(a, run, step.variables())).toList();
            if (holds(need.name(), args, known, run)) continue;
            unmet.add(need.name() + "(" + String.join(", ", args.stream().map(a -> shown(a, projection)).toList()) + ")");
            var made = level < MOST_STEPS ? ways(need.name(), args, known, projection, below, level + 1) : List.<Way>of();
            var next = new ArrayList<Way>();
            for (var way : ways) {
                for (var other : made) {
                    if (way.steps().size() + other.steps().size() < MOST_STEPS) next.add(way.then(other.steps(), other.cost()));
                }
            }
            ways = next;
        }
        if (ways.isEmpty()) {
            trace.open(Trace.Kind.DROPPED, step.effect().name() + ": needs " + String.join(", ", unmet)).append(below).close();
            return List.of();
        }
        trace.open(Trace.Kind.ANSWER, step.title()).append(below).close();
        return ways.stream().map(way -> way.then(List.of(step), step.effect().cost())).toList();
    }

    /** Whether a fact says {@code predicate(args)} holds; one no fact speaks of must be made. */
    private boolean holds(String predicate, List<Value> args, Map<String, Value> known, Run run) {
        for (var fact : facts) {
            if (!fact.holds().name().equals(predicate)) continue;
            var env = new LinkedHashMap<>(known);
            for (var i = 0; i < fact.holds().args().size(); i++) env.put(fact.holds().args().get(i), args.get(i));
            if (Value.truthy(Script.evaluate(fact.when(), run, env))) return true;
        }
        return false;
    }

    /** A way as a plan: its steps' titles in the order they run, their questions, and running them in that order. */
    private Plan plan(Way way) {
        var title = String.join(", then ", way.steps().stream().map(Step::title).toList());
        var asks = way.steps().stream().flatMap(step -> step.asks().stream()).toList();
        return new Plan(title, asks, (projection, answers) -> way.steps().forEach(step -> run(step, projection, answers)));
    }

    /** An element as people read it: by its address. */
    private static String shown(Value fact, Projection projection) {
        return fact instanceof Value.Elem e && projection.program().contains(e.id())
                ? projection.program().element(e.id()).qualified() : Value.asText(fact);
    }

    private void run(Step step, Projection projection, Map<String, String> answers) {
        var args = new HashMap<String, Object>();
        step.effect().body().params().forEach(p -> args.put(p.name(), step.variables().get(p.name())));
        step.asks().forEach(ask -> args.put(ask.name(), answers.getOrDefault(ask.name(), ask.defaultAnswer())));
        step.effect().body().apply(Run.on(projection, library), new Args(args));
    }
}
