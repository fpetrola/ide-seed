package ide.knowledge;

import ide.refactoring.Library;
import ide.refactoring.script.Script;
import ide.refactoring.script.ScriptParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * <pre>
 * goal &lt;code&gt; needs p(a, b)
 * fact p(x, y) when expr
 * effect &lt;name&gt; "title with ${expr}"
 *   makes p(x, y)
 *   [when expr]
 *   [for v in expr]
 *   [needs q(expr, expr)]...          (holds, by a fact, or is made first)
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

    static Knowledge parse(String text, Library library) {
        var lines = text.lines().map(String::stripTrailing).toList();
        var goals = new ArrayList<Knowledge.Goal>();
        var facts = new ArrayList<Knowledge.Fact>();
        var effects = new ArrayList<Knowledge.Effect>();
        for (var i = 0; i < lines.size(); i++) {
            var line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("# ")) continue;
            if (line.startsWith("goal ")) {
                var needs = line.indexOf(" needs ");
                goals.add(new Knowledge.Goal(line.substring(5, needs).strip(), predicate(line.substring(needs + 7))));
            } else if (line.startsWith("fact ")) {
                var when = line.indexOf(" when ");
                facts.add(new Knowledge.Fact(predicate(line.substring(5, when)), line.substring(when + 6).strip()));
            } else if (line.startsWith("effect ")) {
                var rest = line.substring(7).strip();
                var space = rest.indexOf(' ');
                var name = rest.substring(0, space);
                var title = rest.substring(space + 1).strip();
                title = title.substring(1, title.length() - 1);
                Knowledge.Predicate makes = null;
                String when = null, forVariable = null, forItems = null;
                var cost = 1;
                var keepsMeaning = false;
                var needs = new ArrayList<Knowledge.Predicate>();
                var asks = new ArrayList<ide.engine.Plan.Ask>();
                var body = new StringBuilder();
                for (i++; !lines.get(i).strip().equals("end"); i++) {
                    var clause = lines.get(i).strip();
                    if (clause.startsWith("makes ")) makes = predicate(clause.substring(6));
                    else if (clause.startsWith("when ")) when = clause.substring(5);
                    else if (clause.startsWith("needs ")) needs.add(predicate(clause.substring(6)));
                    else if (clause.startsWith("cost ")) cost = Integer.parseInt(clause.substring(5).strip());
                    else if (clause.equals("keeps meaning")) keepsMeaning = true;
                    else if (clause.startsWith("ask ")) {
                        var parts = quoted(clause.substring(4).strip());
                        asks.add(new ide.engine.Plan.Ask(parts.getFirst(), parts.get(1), parts.size() > 2 ? parts.get(2) : ""));
                    }
                    else if (clause.startsWith("for ")) {
                        var in = clause.indexOf(" in ");
                        forVariable = clause.substring(4, in).strip();
                        forItems = clause.substring(in + 4);
                    } else if (clause.equals("do")) { // the script language knows where its block ends
                        var length = ScriptParser.blockLength(lines.subList(i + 1, lines.size()));
                        lines.subList(i + 1, i + 1 + length).forEach(stmt -> body.append(stmt.strip()).append('\n'));
                        i += length + 1;
                    } else if (!clause.isEmpty()) {
                        throw new IllegalArgumentException("knowledge: unexpected clause in effect " + name + ": " + clause);
                    }
                }
                if (makes == null) throw new IllegalArgumentException("knowledge: effect " + name + " makes nothing");
                var variables = new LinkedHashSet<>(List.of("site", "target", "symbol", "path", "line", "column", "arguments"));
                variables.addAll(makes.args());
                if (forVariable != null) variables.add(forVariable);
                asks.forEach(ask -> variables.add(ask.name()));
                effects.add(new Knowledge.Effect(name, title, makes, when, forVariable, forItems, List.copyOf(needs),
                        List.copyOf(asks), cost, keepsMeaning, Script.block(body.toString(), List.copyOf(variables))));
            } else {
                throw new IllegalArgumentException("knowledge: unexpected line: " + line);
            }
        }
        return new Knowledge(goals, facts, effects, library);
    }

    /** {@code p(a, f(b, c))}: a name and its arguments, split at the commas outside parentheses and quotes. */
    private static Knowledge.Predicate predicate(String text) {
        var open = text.indexOf('(');
        var inside = text.substring(open + 1, text.strip().length() - 1);
        var args = new ArrayList<String>();
        var depth = 0;
        var quoted = false;
        var start = 0;
        for (var i = 0; i < inside.length(); i++) {
            var c = inside.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && c == '(') depth++;
            else if (!quoted && c == ')') depth--;
            else if (!quoted && depth == 0 && c == ',') {
                args.add(inside.substring(start, i).strip());
                start = i + 1;
            }
        }
        args.add(inside.substring(start).strip());
        return new Knowledge.Predicate(text.substring(0, open).strip(), List.copyOf(args));
    }

    /** A word, then texts in quotes; a quote inside a {@code ${...}} is the expression's, not the end of the text. */
    private static List<String> quoted(String clause) {
        var parts = new ArrayList<String>();
        var space = clause.indexOf(' ');
        parts.add(space < 0 ? clause : clause.substring(0, space));
        StringBuilder text = null;
        var depth = 0;
        for (var i = space < 0 ? clause.length() : space; i < clause.length(); i++) {
            var c = clause.charAt(i);
            if (text == null) {
                if (c == '"') text = new StringBuilder();
            } else if (c == '"' && depth == 0) {
                parts.add(text.toString());
                text = null;
            } else {
                if (c == '{' && i > 0 && clause.charAt(i - 1) == '$') depth++;
                else if (c == '}' && depth > 0) depth--;
                text.append(c);
            }
        }
        return parts;
    }
}
