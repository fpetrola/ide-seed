package ide.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Datalog program: clauses that define relations from facts and from each other. It is refused when it is made if
 * it could mean two things: a variable that no atom of its body binds (unsafe), or a relation that depends on its own
 * negation (not stratified). What is left has one meaning, which any engine computes alike.
 * <p>
 * As text it is written as any Datalog reads it: {@code ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).}, a
 * variable starting with a capital or {@code _} ({@code _} alone: any value), a constant a word in lower case, a
 * number or a quoted text; {@code not} before an atom, {@code =} and {@code !=} between terms; {@code %} or {@code #}
 * to the end of a line is a comment. A name may have {@code -} inside it.
 */
public final class Rules {

    private final List<Clause> clauses;
    private final Map<String, List<Clause>> defining = new LinkedHashMap<>();
    private final Map<String, Set<String>> components = new HashMap<>();
    private final Set<String> recursive = new HashSet<>();

    private Rules(List<Clause> clauses) {
        this.clauses = List.copyOf(clauses);
        for (var clause : this.clauses) {
            safe(clause);
            defining.computeIfAbsent(clause.head().relation(), relation -> new ArrayList<>()).add(clause);
        }
        stratify();
    }

    public static Rules of(List<Clause> clauses) {
        return new Rules(clauses);
    }

    public static Rules none() {
        return new Rules(List.of());
    }

    public static Rules parse(String text) {
        var parser = new Parser(text);
        var clauses = new ArrayList<Clause>();
        while (parser.more()) clauses.add(parser.clause());
        return new Rules(clauses);
    }

    /** An atom alone, as a query is written. */
    public static Atom atom(String text) {
        var parser = new Parser(text);
        var atom = parser.atom();
        parser.end();
        return atom;
    }

    /** This program and {@code other}'s clauses after its own. */
    public Rules and(Rules other) {
        var all = new ArrayList<>(clauses);
        all.addAll(other.clauses);
        return new Rules(all);
    }

    public List<Clause> clauses() {
        return clauses;
    }

    public boolean defines(String relation) {
        return defining.containsKey(relation);
    }

    /** The clauses whose head is {@code relation}, in the order they were written. */
    public List<Clause> defining(String relation) {
        return defining.getOrDefault(relation, List.of());
    }

    /** Whether a relation depends on itself, directly or through others. */
    public boolean recursive(String relation) {
        return recursive.contains(relation);
    }

    /** The relations that depend on each other with {@code relation}: itself alone when it is not recursive. */
    public Set<String> component(String relation) {
        return components.getOrDefault(relation, Set.of(relation));
    }

    @Override
    public String toString() {
        return String.join("\n", clauses.stream().map(Clause::toString).toList());
    }

    private static void safe(Clause clause) {
        var bound = new HashSet<String>();
        for (var literal : clause.body()) if (literal instanceof Literal.Positive p) bound.addAll(p.variables());
        var asked = new LinkedHashSet<>(clause.head().variables());
        for (var literal : clause.body()) if (!(literal instanceof Literal.Positive)) asked.addAll(literal.variables());
        for (var variable : asked) {
            if (!bound.contains(variable)) {
                throw new IllegalArgumentException("unsafe clause " + clause + ": " + variable + " is bound by no atom of its body");
            }
        }
    }

    /** The strongly connected components of what defines what (Tarjan's), checking no negation closes a cycle. */
    private void stratify() {
        var index = new HashMap<String, Integer>();
        var low = new HashMap<String, Integer>();
        var stack = new ArrayDeque<String>();
        var onStack = new HashSet<String>();
        var counter = new int[1];
        for (var relation : defining.keySet()) {
            if (!index.containsKey(relation)) connect(relation, index, low, stack, onStack, counter);
        }
        for (var clause : clauses) {
            var head = clause.head().relation();
            for (var literal : clause.body()) {
                if (literal instanceof Literal.Negative n && component(head).contains(n.atom().relation())) {
                    throw new IllegalArgumentException("not stratified: " + head + " depends on its own negation through " + literal);
                }
                if (literal instanceof Literal.Positive p && p.atom().relation().equals(head)) recursive.add(head);
            }
        }
    }

    private void connect(String relation, Map<String, Integer> index, Map<String, Integer> low, Deque<String> stack,
                         Set<String> onStack, int[] counter) {
        index.put(relation, counter[0]);
        low.put(relation, counter[0]++);
        stack.push(relation);
        onStack.add(relation);
        for (var clause : defining(relation)) {
            for (var literal : clause.body()) {
                var next = switch (literal) {
                    case Literal.Positive p -> p.atom().relation();
                    case Literal.Negative n -> n.atom().relation();
                    case Literal.Comparison c -> null;
                };
                if (next == null || !defines(next)) continue;
                if (!index.containsKey(next)) {
                    connect(next, index, low, stack, onStack, counter);
                    low.put(relation, Math.min(low.get(relation), low.get(next)));
                } else if (onStack.contains(next)) {
                    low.put(relation, Math.min(low.get(relation), index.get(next)));
                }
            }
        }
        if (low.get(relation).equals(index.get(relation))) {
            var component = new LinkedHashSet<String>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(relation));
            var frozen = Set.copyOf(component);
            for (var r : component) components.put(r, frozen);
            if (component.size() > 1) recursive.addAll(component);
        }
    }

    /** Datalog as text, read left to right. */
    private static final class Parser {

        private final String text;
        private int at;
        private int anonymous;

        Parser(String text) {
            this.text = text;
        }

        boolean more() {
            skip();
            return at < text.length();
        }

        void end() {
            if (more()) throw error("the end");
        }

        Clause clause() {
            anonymous = 0;
            var head = atom();
            var body = new ArrayList<Literal>();
            skip();
            if (text.startsWith(":-", at)) {
                at += 2;
                do body.add(literal());
                while (next(','));
            }
            if (!next('.')) throw error("'.'");
            return new Clause(head, body);
        }

        Literal literal() {
            skip();
            if (text.startsWith("not", at) && at + 3 < text.length() && Character.isWhitespace(text.charAt(at + 3))) {
                at += 3;
                return new Literal.Negative(atom());
            }
            var start = at;
            var word = word();
            skip();
            if (word != null && at < text.length() && text.charAt(at) == '(' && !variable(word)) {
                at = start;
                return new Literal.Positive(atom());
            }
            at = start;
            var left = term();
            skip();
            boolean equal;
            if (text.startsWith("!=", at)) {
                at += 2;
                equal = false;
            } else if (next('=')) {
                equal = true;
            } else {
                throw error("an atom, or a comparison with = or !=");
            }
            return new Literal.Comparison(left, equal, term());
        }

        Atom atom() {
            skip();
            var name = word();
            if (name == null || variable(name)) throw error("the name of a relation");
            if (!next('(')) throw error("'('");
            var terms = new ArrayList<Term>();
            if (!next(')')) {
                do terms.add(term());
                while (next(','));
                if (!next(')')) throw error("')'");
            }
            return new Atom(name, terms);
        }

        Term term() {
            skip();
            if (at < text.length() && text.charAt(at) == '"') {
                var value = new StringBuilder();
                at++;
                while (at < text.length() && text.charAt(at) != '"') {
                    if (text.charAt(at) == '\\' && at + 1 < text.length()) at++;
                    value.append(text.charAt(at++));
                }
                if (at >= text.length()) throw error("the closing '\"'");
                at++;
                return new Term.Constant(value.toString());
            }
            var word = word();
            if (word == null) throw error("a variable or a constant");
            if (word.equals("_")) return new Term.Variable("_" + ++anonymous);
            return variable(word) ? new Term.Variable(word) : new Term.Constant(word);
        }

        private static boolean variable(String word) {
            return Character.isUpperCase(word.charAt(0)) || word.charAt(0) == '_';
        }

        /** Letters, digits, {@code _}, and {@code -} inside a name; null if none is here. */
        private String word() {
            skip();
            var start = at;
            while (at < text.length()) {
                var c = text.charAt(at);
                if (Character.isLetterOrDigit(c) || c == '_' || (c == '-' && at > start && at + 1 < text.length()
                        && Character.isLetterOrDigit(text.charAt(at + 1)))) {
                    at++;
                } else {
                    break;
                }
            }
            return at == start ? null : text.substring(start, at);
        }

        private boolean next(char c) {
            skip();
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void skip() {
            while (at < text.length()) {
                var c = text.charAt(at);
                if (Character.isWhitespace(c)) {
                    at++;
                } else if (c == '%' || c == '#') {
                    while (at < text.length() && text.charAt(at) != '\n') at++;
                } else {
                    break;
                }
            }
        }

        private IllegalArgumentException error(String expected) {
            return new IllegalArgumentException("rules: expected " + expected + " at " + at + " in: " + text);
        }
    }
}
