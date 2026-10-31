package ide.knowledge;

import ide.coherence.Plan;
import ide.coherence.QuickFix;
import ide.coherence.Remedy;
import ide.coherence.Violation;
import ide.core.Param;
import ide.graph.Diagnostic;
import ide.graph.LanguageRules;
import ide.graph.Program;
import ide.refactor.Args;
import ide.refactor.Ctx;
import ide.refactor.Refactoring;
import ide.refactor.script.Script;

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
                  int cost, Script body) {
    }

    /** One step of a search, for {@code why}. */
    public record Trace(List<String> lines, List<Plan> plans) {
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
    public List<Plan> plans(Violation violation, Program program, LanguageRules rules) {
        var facts = new HashMap<String, Object>();
        if (violation.site() != null) facts.put("site", violation.site().value());
        if (violation.target() != null) facts.put("target", violation.target().value());
        return search(violation.code(), facts, program, rules).plans();
    }

    @Override
    public List<Plan> plans(Diagnostic diagnostic, Program program, LanguageRules rules) {
        return search(diagnostic.code(), Map.of("symbol", diagnostic.symbol(), "path", diagnostic.path(),
                "line", String.valueOf(diagnostic.line())), program, rules).plans();
    }

    /** The search for a code with the facts that came with it, with a readable trace. */
    public Trace search(String code, Map<String, Object> facts, Program program, LanguageRules rules) {
        var lines = new ArrayList<String>();
        var candidates = new ArrayList<Map.Entry<Integer, Plan>>();
        for (var goal : goals) {
            if (!goal.code().equals(code)) continue;
            var bound = goal.needs().args().stream().map(a -> String.valueOf(facts.get(a))).toList();
            lines.add("? " + goal.needs().name() + "(" + String.join(", ", bound) + ")");
            for (var effect : effects) {
                if (!effect.makes().name().equals(goal.needs().name())) continue;
                var env = new LinkedHashMap<String, Object>(facts);
                for (var i = 0; i < effect.makes().args().size(); i++) {
                    env.put(effect.makes().args().get(i), facts.get(goal.needs().args().get(i)));
                }
                var ctx = ctx(program, rules);
                if (effect.when() != null && !Script.truthy(Script.evaluate(effect.when(), ctx, env))) {
                    lines.add("  X " + effect.name() + ": " + effect.when());
                    continue;
                }
                var items = effect.forVariable() == null ? java.util.Collections.singletonList(null)
                        : Script.list(Script.evaluate(effect.forItems(), ctx, env));
                var rank = 0;
                for (var item : items) {
                    var variables = new LinkedHashMap<>(env);
                    if (effect.forVariable() != null) variables.put(effect.forVariable(), item);
                    var title = Script.render(effect.title(), ctx, variables);
                    lines.add("  ! " + title);
                    candidates.add(Map.entry(effect.cost() * 100 + rank++, new Plan(title, p -> run(effect, p, rules, variables))));
                }
            }
        }
        candidates.sort(Map.Entry.comparingByKey());
        return new Trace(lines, candidates.stream().map(Map.Entry::getValue).toList());
    }

    private void run(Effect effect, Program program, LanguageRules rules, Map<String, Object> variables) {
        var args = new HashMap<String, Object>();
        effect.body().params().forEach(p -> args.put(p.name(), variables.get(p.name())));
        effect.body().apply(ctx(program, rules), new Args(args));
    }

    private Ctx ctx(Program program, LanguageRules rules) {
        return new Ctx() {
            @Override
            public Program program() {
                return program;
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
            public List<Param> params(String refactoring) {
                return library.apply(refactoring).params();
            }
        };
    }
}
