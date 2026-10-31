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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative knowledge: goals say what must hold for a violation or diagnostic to go away, effects say what
 * makes a predicate true and how. Plans are deduced by matching a goal's predicate to the effects that make it,
 * binding the goal's arguments to the effect's variables by position, enumerating {@code for} candidates and
 * keeping those whose {@code when} holds, cheapest first.
 */
public final class Knowledge implements Remedy, QuickFix {

    record Predicate(String name, List<String> args) {
    }

    record Goal(String code, Predicate needs) {
    }

    record Effect(String name, String title, Predicate makes, String when, String forVariable, String forItems,
                  List<Plan.Ask> asks, int cost, Script body) {
    }

    /** A search: the plans found, cheapest first, and how they were deduced, for {@code why}. */
    public record Search(Trace trace, List<Plan> plans) {
    }

    private final List<Goal> goals;
    private final List<Effect> effects;
    private final Library library;

    Knowledge(List<Goal> goals, List<Effect> effects, Library library) {
        this.goals = List.copyOf(goals);
        this.effects = List.copyOf(effects);
        this.library = library;
    }

    public static Knowledge parse(String text, Library library) {
        return KnowledgeParser.parse(text, library);
    }

    @Override
    public List<Plan> plans(Violation violation, Projection projection) {
        var facts = new HashMap<String, Object>(violation.facts());
        if (violation.site() != null) facts.put("site", violation.site());
        if (violation.target() != null) facts.put("target", violation.target());
        return search(violation.code(), facts, projection).plans();
    }

    @Override
    public List<Plan> plans(Diagnostic diagnostic, Projection projection) {
        return search(diagnostic.code(), Map.of("symbol", diagnostic.symbol(), "path", diagnostic.path(),
                "line", String.valueOf(diagnostic.line())), projection).plans();
    }

    /**
     * The search for a code with the facts that came with it (element ids, text): each goal a question, each effect
     * tried an answer or dropped.
     */
    public Search search(String code, Map<String, ?> given, Projection projection) {
        var facts = new LinkedHashMap<String, Value>();
        given.forEach((name, fact) -> facts.put(name, Value.of(fact)));
        var trace = new Trace();
        var candidates = new ArrayList<Map.Entry<Integer, Plan>>();
        for (var goal : goals) {
            if (!goal.code().equals(code)) continue;
            var bound = goal.needs().args().stream().map(a -> shown(facts.get(a), projection)).toList();
            trace.open(Trace.Kind.QUESTION, goal.needs().name() + "(" + String.join(", ", bound) + ")");
            for (var effect : effects) {
                if (!effect.makes().name().equals(goal.needs().name())) continue;
                var env = new LinkedHashMap<>(facts);
                for (var i = 0; i < effect.makes().args().size(); i++) {
                    env.put(effect.makes().args().get(i), facts.get(goal.needs().args().get(i)));
                }
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
                        var title = Script.render(effect.title(), run, variables);
                        trace.add(Trace.Kind.ANSWER, title);
                        var asks = effect.asks().stream().map(a -> new Plan.Ask(a.name(), a.question(),
                                Script.render(a.defaultAnswer(), run, variables))).toList();
                        candidates.add(Map.entry(effect.cost() * 100 + rank++, new Plan(title, asks,
                                (p, answers) -> run(effect, asks, p, variables, answers))));
                    }
                } catch (RuntimeException inapplicable) { // e.g. the facts name elements that no longer exist
                    trace.add(Trace.Kind.DROPPED, effect.name() + ": " + inapplicable.getMessage());
                }
            }
            trace.close();
        }
        candidates.sort(Map.Entry.comparingByKey());
        return new Search(trace, candidates.stream().map(Map.Entry::getValue).toList());
    }

    /** An element as people read it: by its address. */
    private static String shown(Value fact, Projection projection) {
        return fact instanceof Value.Elem e && projection.program().contains(e.id())
                ? projection.program().element(e.id()).qualified() : Value.asText(fact);
    }

    private void run(Effect effect, List<Plan.Ask> asks, Projection projection, Map<String, Value> variables,
                     Map<String, String> answers) {
        var args = new HashMap<String, Object>();
        effect.body().params().forEach(p -> args.put(p.name(), variables.get(p.name())));
        asks.forEach(ask -> args.put(ask.name(), answers.getOrDefault(ask.name(), ask.defaultAnswer())));
        effect.body().apply(Run.on(projection, library), new Args(args));
    }
}
