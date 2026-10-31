package ide.knowledge;

import ide.engine.Plan;
import ide.engine.QuickFix;
import ide.engine.Remedy;
import ide.engine.Trace;
import ide.engine.Violation;
import ide.core.Param;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Projection;
import ide.refactoring.Args;
import ide.refactoring.Ctx;
import ide.refactoring.Refactoring;
import ide.refactoring.script.Script;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
    private final Function<String, Refactoring> library;

    Knowledge(List<Goal> goals, List<Effect> effects, Function<String, Refactoring> library) {
        this.goals = List.copyOf(goals);
        this.effects = List.copyOf(effects);
        this.library = library;
    }

    public static Knowledge parse(String text, Function<String, Refactoring> library) {
        return KnowledgeParser.parse(text, library);
    }

    @Override
    public List<Plan> plans(Violation violation, Projection projection, LanguageRules rules) {
        var facts = new HashMap<String, Object>();
        if (violation.site() != null) facts.put("site", violation.site().value());
        if (violation.target() != null) facts.put("target", violation.target().value());
        facts.putAll(violation.facts());
        return search(violation.code(), facts, projection, rules).plans();
    }

    @Override
    public List<Plan> plans(Diagnostic diagnostic, Projection projection, LanguageRules rules) {
        return search(diagnostic.code(), Map.of("symbol", diagnostic.symbol(), "path", diagnostic.path(),
                "line", String.valueOf(diagnostic.line())), projection, rules).plans();
    }

    /** The search for a code with the facts that came with it: each goal a question, each effect tried an answer or dropped. */
    public Search search(String code, Map<String, Object> facts, Projection projection, LanguageRules rules) {
        var trace = new Trace();
        var candidates = new ArrayList<Map.Entry<Integer, Plan>>();
        for (var goal : goals) {
            if (!goal.code().equals(code)) continue;
            var bound = goal.needs().args().stream().map(a -> String.valueOf(facts.get(a))).toList();
            trace.open(Trace.Kind.QUESTION, goal.needs().name() + "(" + String.join(", ", bound) + ")");
            for (var effect : effects) {
                if (!effect.makes().name().equals(goal.needs().name())) continue;
                var env = new LinkedHashMap<String, Object>(facts);
                for (var i = 0; i < effect.makes().args().size(); i++) {
                    env.put(effect.makes().args().get(i), facts.get(goal.needs().args().get(i)));
                }
                var ctx = ctx(projection, rules);
                try {
                    if (effect.when() != null && !Script.truthy(Script.evaluate(effect.when(), ctx, env))) {
                        trace.add(Trace.Kind.DROPPED, effect.name() + ": " + effect.when());
                        continue;
                    }
                    var items = effect.forVariable() == null ? java.util.Collections.singletonList(null)
                            : Script.list(Script.evaluate(effect.forItems(), ctx, env));
                    var rank = 0;
                    for (var item : items) {
                        var variables = new LinkedHashMap<>(env);
                        if (effect.forVariable() != null) variables.put(effect.forVariable(), item);
                        var title = Script.render(effect.title(), ctx, variables);
                        trace.add(Trace.Kind.ANSWER, title);
                        var asks = effect.asks().stream().map(a -> new Plan.Ask(a.name(), a.question(),
                                Script.render(a.defaultAnswer(), ctx, variables))).toList();
                        candidates.add(Map.entry(effect.cost() * 100 + rank++, new Plan(title, asks,
                                (p, answers) -> run(effect, asks, p, rules, variables, answers))));
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

    private void run(Effect effect, List<Plan.Ask> asks, Projection projection, LanguageRules rules,
                     Map<String, Object> variables, Map<String, String> answers) {
        var args = new HashMap<String, Object>();
        effect.body().params().forEach(p -> args.put(p.name(), variables.get(p.name())));
        asks.forEach(ask -> args.put(ask.name(), answers.getOrDefault(ask.name(), ask.defaultAnswer())));
        effect.body().apply(ctx(projection, rules), new Args(args));
    }

    private Ctx ctx(Projection projection, LanguageRules rules) {
        return new Ctx() {
            @Override
            public Projection projection() {
                return projection;
            }

            @Override
            public String call(String refactoring, Map<String, ?> args) {
                return library.apply(refactoring).apply(this, new Args(args));
            }

            @Override
            public LanguageRules rules() {
                return rules;
            }

            @Override
            public void oblige(String assumption) {
                // remedies run inside the repairer; what they assume is not tracked yet
            }

            @Override
            public boolean attempt(String label, Runnable body) {
                body.run(); // effects are judged as a whole by the repairer
                return true;
            }

            @Override
            public List<Param> params(String refactoring) {
                return library.apply(refactoring).params();
            }
        };
    }
}
