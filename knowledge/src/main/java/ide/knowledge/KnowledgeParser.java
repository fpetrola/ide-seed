package ide.knowledge;

import ide.refactoring.Library;
import ide.refactoring.script.Script;
import ide.refactoring.script.ScriptParser;

import ide.rules.Atom;
import ide.rules.Clause;
import ide.rules.Literal;
import ide.rules.Rules;
import ide.rules.Term;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * <pre>
 * goal &lt;code&gt; needs p(a, b)
 * def p(x, "constant") when q(x, y) and not r(y, _) and x != y     (Datalog: a name is a variable, a quoted text a
 *   and s(y)                                                        constant, _ any value; it goes on in "and" lines)
 * order name "low" "high" ...      (facts: name-value(v), and name-at-least(a, b) for each a not below b)
 * say [not] relation for p "title with ${element}, ${was} and ${value}"   (how a change made for p is said)
 * rule name "what the language says"     (a constraint: where it does not hold, violates-name(vars) does)
 *   for v, w where q(v, w) and not r(w)
 *   must s(v) or not t(w)
 * end
 * problem &lt;code&gt; is name(expr, expr)    (the code is the rule broken there: the plans make it hold)
 * promise relation of kind "why"         (no change of relation on an element of that kind is ever deduced)
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
        var clauses = new ArrayList<Clause>();
        var says = new HashMap<String, String>();
        var promises = new HashMap<String, String>();
        for (var i = 0; i < lines.size(); i++) {
            var line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("# ")) continue;
            if (line.startsWith("def ")) {
                var definition = new StringBuilder(line.substring(4));
                while (i + 1 < lines.size() && lines.get(i + 1).strip().startsWith("and ")) definition.append(' ').append(lines.get(++i).strip());
                clauses.add(definition(definition.toString()));
            } else if (line.startsWith("order ")) {
                var parts = quoted(line.substring(6).strip());
                var name = parts.getFirst();
                var values = parts.subList(1, parts.size());
                for (var value : values) clauses.add(new Clause(Atom.of(name + "-value", Term.constant(value)), List.of()));
                for (var high = 0; high < values.size(); high++) {
                    for (var low = 0; low <= high; low++) {
                        clauses.add(new Clause(Atom.of(name + "-at-least", Term.constant(values.get(high)), Term.constant(values.get(low))), List.of()));
                    }
                }
            } else if (line.startsWith("say ")) {
                var said = java.util.regex.Pattern.compile("say (not )?(\\S+) for (\\S+) \"(.*)\"").matcher(line);
                if (!said.matches()) throw new IllegalArgumentException("knowledge: expected say [not] <relation> for <predicate> \"title\": " + line);
                says.put((said.group(1) == null ? "" : "not ") + said.group(2) + " for " + said.group(3), said.group(4));
            } else if (line.startsWith("promise ")) {
                var promised = java.util.regex.Pattern.compile("promise (\\S+) of (\\S+) \"(.*)\"").matcher(line);
                if (!promised.matches()) throw new IllegalArgumentException("knowledge: expected promise <relation> of <kind> \"why\": " + line);
                promises.put(promised.group(1) + " of " + promised.group(2), promised.group(3));
            } else if (line.startsWith("problem ")) {
                var is = line.indexOf(" is ");
                var broken = predicate(line.substring(is + 4));
                goals.add(new Knowledge.Goal(line.substring(8, is).strip(), new Knowledge.Predicate("violates-" + broken.name(), broken.args()), false));
            } else if (line.startsWith("rule ")) {
                String where = null;
                String must = null;
                List<String> variables = List.of();
                var name = line.substring(5).strip().split("\\s+", 2)[0];
                for (i++; !lines.get(i).strip().equals("end"); i++) {
                    var clause = lines.get(i).strip();
                    if (clause.startsWith("for ")) {
                        var written = new StringBuilder(clause);
                        while (lines.get(i + 1).strip().startsWith("and ")) written.append(' ').append(lines.get(++i).strip());
                        var at = written.indexOf(" where ");
                        variables = split(written.substring(4, at < 0 ? written.length() : at), ",").stream().map(String::strip).toList();
                        where = at < 0 ? null : written.substring(at + 7);
                    } else if (clause.startsWith("must ")) {
                        must = clause.substring(5);
                    } else if (!clause.isEmpty() && !clause.startsWith("# ")) {
                        throw new IllegalArgumentException("knowledge: unexpected clause in rule " + name + ": " + clause);
                    }
                }
                if (must == null) throw new IllegalArgumentException("knowledge: rule " + name + " says nothing it must");
                var head = "violates-" + name + "(" + String.join(", ", variables) + ")";
                var body = new ArrayList<String>();
                if (where != null) body.add(where);
                for (var alternative : split(must, " or ")) body.add(negated(alternative.strip()));
                clauses.add(definition(head + " when " + String.join(" and ", body)));
            } else if (line.startsWith("goal ")) {
                var needs = line.indexOf(" needs ");
                goals.add(new Knowledge.Goal(line.substring(5, needs).strip(), predicate(line.substring(needs + 7)), true));
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
        return new Knowledge(goals, facts, effects, Rules.of(clauses), says, promises, library);
    }

    /** {@code def head when literal and literal ...}: one clause of Datalog. */
    private static Clause definition(String text) {
        var when = text.indexOf(" when ");
        var head = atom(when < 0 ? text : text.substring(0, when));
        var body = new ArrayList<Literal>();
        var anonymous = new int[1];
        if (when >= 0) {
            for (var part : split(text.substring(when + 6), " and ")) {
                var literal = part.strip();
                if (literal.startsWith("not ")) {
                    body.add(new Literal.Negative(atom(literal.substring(4), anonymous)));
                } else if (literal.contains("(")) {
                    body.add(new Literal.Positive(atom(literal, anonymous)));
                } else {
                    var unequal = literal.indexOf("!=");
                    var equal = unequal >= 0 ? unequal : literal.indexOf('=');
                    if (equal < 0) throw new IllegalArgumentException("knowledge: expected an atom or a comparison: " + literal);
                    body.add(new Literal.Comparison(term(literal.substring(0, equal), anonymous), unequal < 0,
                            term(literal.substring(equal + (unequal >= 0 ? 2 : 1)), anonymous)));
                }
            }
        }
        return new Clause(head, body);
    }

    /** A literal that holds where {@code literal} does not: an atom and its negation, {@code =} and {@code !=}. */
    private static String negated(String literal) {
        if (literal.startsWith("not ")) return literal.substring(4);
        if (literal.contains("(")) return "not " + literal;
        if (literal.contains("!=")) return literal.replace("!=", "=");
        return literal.replace("=", "!=");
    }

    private static Atom atom(String text) {
        return atom(text, new int[1]);
    }

    private static Atom atom(String text, int[] anonymous) {
        var predicate = predicate(text.strip());
        return new Atom(predicate.name(), predicate.args().stream().map(arg -> term(arg, anonymous)).toList());
    }

    /** A quoted text is a constant; {@code _} any value; any other word a variable. */
    private static Term term(String text, int[] anonymous) {
        var word = text.strip();
        if (word.startsWith("\"") && word.endsWith("\"") && word.length() >= 2) return Term.constant(word.substring(1, word.length() - 1));
        if (word.equals("_")) return Term.variable("_" + ++anonymous[0]);
        return Term.variable(word);
    }

    /** {@code text} split at {@code separator} where it is outside quotes and parentheses. */
    private static List<String> split(String text, String separator) {
        var parts = new ArrayList<String>();
        var depth = 0;
        var quoted = false;
        var start = 0;
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (!quoted && c == '(') depth++;
            else if (!quoted && c == ')') depth--;
            else if (!quoted && depth == 0 && text.startsWith(separator, i)) {
                parts.add(text.substring(start, i));
                start = i + separator.length();
                i = start - 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
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
