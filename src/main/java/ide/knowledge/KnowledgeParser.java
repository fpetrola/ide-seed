package ide.knowledge;

import ide.refactoring.Refactoring;
import ide.refactoring.script.Script;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

/**
 * <pre>
 * goal &lt;code&gt; needs p(a, b)
 * effect &lt;name&gt; "title with ${expr}"
 *   makes p(x, y)
 *   [when expr]
 *   [for v in expr]
 *   [cost n]
 *   [ask name "question" "default"]   (a hole: quick fixes only)
 *   do
 *     ...script statements over the variables...
 *   end
 * end
 * </pre>
 */
final class KnowledgeParser {

    private KnowledgeParser() {
    }

    static Knowledge parse(String text, Function<String, Refactoring> library) {
        var lines = text.lines().map(String::stripTrailing).toList();
        var goals = new ArrayList<Knowledge.Goal>();
        var effects = new ArrayList<Knowledge.Effect>();
        for (var i = 0; i < lines.size(); i++) {
            var line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("# ")) continue;
            if (line.startsWith("goal ")) {
                var needs = line.indexOf(" needs ");
                goals.add(new Knowledge.Goal(line.substring(5, needs).strip(), predicate(line.substring(needs + 7))));
            } else if (line.startsWith("effect ")) {
                var rest = line.substring(7).strip();
                var space = rest.indexOf(' ');
                var name = rest.substring(0, space);
                var title = rest.substring(space + 1).strip();
                title = title.substring(1, title.length() - 1);
                Knowledge.Predicate makes = null;
                String when = null, forVariable = null, forItems = null;
                var cost = 1;
                var asks = new ArrayList<ide.engine.Plan.Ask>();
                var body = new StringBuilder();
                for (i++; !lines.get(i).strip().equals("end"); i++) {
                    var clause = lines.get(i).strip();
                    if (clause.startsWith("makes ")) makes = predicate(clause.substring(6));
                    else if (clause.startsWith("when ")) when = clause.substring(5);
                    else if (clause.startsWith("cost ")) cost = Integer.parseInt(clause.substring(5).strip());
                    else if (clause.startsWith("ask ")) {
                        var parts = clause.substring(4).strip().split("\"");
                        asks.add(new ide.engine.Plan.Ask(parts[0].strip(), parts[1], parts.length > 3 ? parts[3] : ""));
                    }
                    else if (clause.startsWith("for ")) {
                        var in = clause.indexOf(" in ");
                        forVariable = clause.substring(4, in).strip();
                        forItems = clause.substring(in + 4);
                    } else if (clause.equals("do")) {
                        var depth = 0;
                        for (i++; depth > 0 || !lines.get(i).strip().equals("end"); i++) {
                            var stmt = lines.get(i).strip();
                            if (stmt.startsWith("for ") || stmt.startsWith("if ")) depth++;
                            if (stmt.equals("end")) depth--;
                            body.append(stmt).append('\n');
                        }
                    } else if (!clause.isEmpty()) {
                        throw new IllegalArgumentException("knowledge: unexpected clause in effect " + name + ": " + clause);
                    }
                }
                if (makes == null) throw new IllegalArgumentException("knowledge: effect " + name + " makes nothing");
                var variables = new LinkedHashSet<>(List.of("site", "target", "symbol", "path", "line"));
                variables.addAll(makes.args());
                if (forVariable != null) variables.add(forVariable);
                asks.forEach(ask -> variables.add(ask.name()));
                effects.add(new Knowledge.Effect(name, title, makes, when, forVariable, forItems, List.copyOf(asks), cost,
                        Script.block(body.toString(), List.copyOf(variables))));
            } else {
                throw new IllegalArgumentException("knowledge: unexpected line: " + line);
            }
        }
        return new Knowledge(goals, effects, library);
    }

    private static Knowledge.Predicate predicate(String text) {
        var open = text.indexOf('(');
        var args = Arrays.stream(text.substring(open + 1, text.strip().length() - 1).split(",")).map(String::strip).toList();
        return new Knowledge.Predicate(text.substring(0, open).strip(), args);
    }
}
