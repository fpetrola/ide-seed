package ide.refactoring.script;

import ide.core.Param;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parses {@code .ref} text: one statement per line, blocks closed by {@code end}, {@code #} comments.
 * <pre>
 * refactoring &lt;id&gt; ["description"] [changes meaning]
 *   param &lt;name&gt; : text|lines|bool|element(KINDS)|elements(KINDS)|choice(A,B) ["description"] [default "template"] [from "expr"]
 *   let v = expr  |  v := &lt;id&gt; args  |  &lt;id&gt; args  |  for x in expr ... end  |  if expr ... [else ...] end
 *   try "label" ... end
 *   require expr "message"  |  oblige "assumption"  |  fail "message"  |  return arg
 * end
 * intent &lt;id&gt; ["description"]
 *   param ...                  (as a refactoring's)
 *   want what is to hold       (in the planner's language; it goes on in lines that start with "and")
 * end
 * </pre>
 */
public final class ScriptParser {

    private final List<String> lines;
    private int next;

    private ScriptParser(String text) {
        this.lines = text.lines().map(String::strip).toList();
    }

    public static List<Script> parse(String text) {
        var parser = new ScriptParser(text);
        var scripts = new ArrayList<Script>();
        while (parser.skipBlank()) scripts.add(parser.refactoring());
        return scripts;
    }

    /** How many of {@code lines} a block of statements takes, up to (not including) the {@code end} that closes it. */
    public static int blockLength(List<String> lines) {
        var parser = new ScriptParser(String.join("\n", lines));
        parser.block("end");
        return parser.next;
    }

    private boolean skipBlank() {
        while (next < lines.size() && (lines.get(next).isEmpty() || lines.get(next).startsWith("# "))) next++;
        return next < lines.size();
    }

    private Script refactoring() {
        var header = lines.get(next++);
        var intent = header.startsWith("intent ");
        if (!intent && !header.startsWith("refactoring ")) throw error("expected 'refactoring <id>' or 'intent <id>'", header);
        var rest = header.substring(header.indexOf(' ') + 1).strip();
        var changesMeaning = rest.endsWith(" changes meaning");
        if (changesMeaning) rest = rest.substring(0, rest.length() - " changes meaning".length()).strip();
        var space = rest.indexOf(' ');
        var id = space < 0 ? rest : rest.substring(0, space);
        var description = space < 0 ? "" : unquote(rest.substring(space + 1).strip());
        var params = new ArrayList<Param>();
        var defaults = new java.util.LinkedHashMap<String, String>();
        var sources = new java.util.LinkedHashMap<String, String>();
        while (skipBlank() && lines.get(next).startsWith("param ")) {
            var line = lines.get(next++);
            var from = line.indexOf(" from \"");
            if (from >= 0) {
                sources.put(line.substring("param ".length(), line.indexOf(':')).strip(), unquote(line.substring(from + 6).strip()));
                line = line.substring(0, from);
            }
            var suggested = line.indexOf(" default \"");
            if (suggested >= 0) {
                defaults.put(line.substring("param ".length(), line.indexOf(':')).strip(), unquote(line.substring(suggested + 9).strip()));
                line = line.substring(0, suggested);
            }
            params.add(param(line));
        }
        var body = intent ? List.<Stmt>of(want()) : block("end");
        next++;
        var script = new Script(id, description, params, defaults, sources, body, intent ? "intent" : "script");
        return changesMeaning ? script.changingMeaning() : script;
    }

    /** An intent's one statement: {@code want ...}, going on in the lines that start with {@code and}, then {@code end}. */
    private Stmt want() {
        if (!skipBlank() || !lines.get(next).startsWith("want ")) throw error("an intent says what it wants: 'want ...'", next < lines.size() ? lines.get(next) : "");
        var want = new StringBuilder(lines.get(next++).substring("want ".length()).strip());
        while (next < lines.size() && lines.get(next).startsWith("and ")) want.append(' ').append(lines.get(next++));
        if (!skipBlank() || !lines.get(next).equals("end")) throw error("an intent wants one thing, then 'end'", next < lines.size() ? lines.get(next) : "");
        return new Stmt.Want(want.toString());
    }

    private Param param(String line) {
        var colon = line.indexOf(':');
        var name = line.substring("param ".length(), colon).strip();
        var spec = line.substring(colon + 1).strip();
        var description = "";
        var quote = spec.indexOf('"');
        if (quote >= 0) {
            description = unquote(spec.substring(quote).strip());
            spec = spec.substring(0, quote).strip();
        }
        var open = spec.indexOf('(');
        var type = open < 0 ? spec : spec.substring(0, open);
        var kinds = open < 0 ? List.<String>of()
                : Arrays.stream(spec.substring(open + 1, spec.length() - 1).split(",")).map(String::strip).toList();
        return new Param(name, type, kinds, description, true);
    }

    /** Statements up to (not including) a line equal to one of {@code terminators}. */
    private List<Stmt> block(String... terminators) {
        var statements = new ArrayList<Stmt>();
        while (true) {
            if (!skipBlank()) throw error("missing 'end'", "");
            var line = lines.get(next);
            if (Arrays.asList(terminators).contains(line)) return statements;
            next++;
            statements.add(statement(line));
        }
    }

    private Stmt statement(String line) {
        if (line.startsWith("let ")) {
            var eq = line.indexOf('=');
            return new Stmt.Let(line.substring(4, eq).strip(), expression(line.substring(eq + 1)));
        }
        if (line.startsWith("for ")) {
            var in = line.indexOf(" in ");
            var variable = line.substring(4, in).strip();
            var items = expression(line.substring(in + 4));
            var body = block("end");
            next++;
            return new Stmt.For(variable, items, body);
        }
        if (line.startsWith("if ")) {
            var condition = expression(line.substring(3));
            var then = block("else", "end");
            List<Stmt> otherwise = List.of();
            if (lines.get(next++).equals("else")) {
                otherwise = block("end");
                next++;
            }
            return new Stmt.If(condition, then, otherwise);
        }
        if (line.startsWith("try ")) {
            var label = unquote(line.substring(4).strip());
            var body = block("end");
            next++;
            return new Stmt.Try(label, body);
        }
        if (line.startsWith("require ")) {
            var tokens = new Tokens(line.substring(8));
            var condition = tokens.expression();
            return new Stmt.Require(condition, tokens.remainingString());
        }
        if (line.startsWith("fail ")) return new Stmt.Fail(unquote(line.substring(5).strip()));
        if (line.startsWith("oblige ")) return new Stmt.Oblige(unquote(line.substring(7).strip()));
        if (line.startsWith("return ")) return new Stmt.Return(unquoteArg(line.substring(7).strip()));
        var assign = line.indexOf(":=");
        var variable = assign < 0 ? null : line.substring(0, assign).strip();
        var call = splitArgs(assign < 0 ? line : line.substring(assign + 2).strip());
        return new Stmt.Call(variable, call.getFirst(), call.subList(1, call.size()).stream().map(ScriptParser::unquoteArg).toList());
    }

    static Expr expression(String text) {
        var tokens = new Tokens(text);
        var expr = tokens.expression();
        tokens.end();
        return expr;
    }

    /** Splits on spaces outside quotes and {@code ${...}}. */
    static List<String> splitArgs(String text) {
        var args = new ArrayList<String>();
        var current = new StringBuilder();
        var depth = 0;
        var quoted = false;
        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == '"' && depth == 0) quoted = !quoted;
            if (c == '{' && i > 0 && text.charAt(i - 1) == '$') depth++;
            if (c == '}' && depth > 0) depth--;
            if (Character.isWhitespace(c) && depth == 0 && !quoted) {
                if (!current.isEmpty()) args.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) args.add(current.toString());
        return args;
    }

    /** An argument without its quotes: a quoted value, or a quoted value given by name ({@code facts="body=..."}). */
    private static String unquoteArg(String arg) {
        var named = NAMED_QUOTED.matcher(arg);
        if (named.matches()) return named.group(1) + "=" + named.group(2);
        return arg.startsWith("\"") && arg.endsWith("\"") && arg.length() >= 2 ? arg.substring(1, arg.length() - 1) : arg;
    }

    private static final java.util.regex.Pattern NAMED_QUOTED = java.util.regex.Pattern.compile("([\\w-]+)=\"(.*)\"", java.util.regex.Pattern.DOTALL);

    private static String unquote(String text) {
        if (!text.startsWith("\"") || !text.endsWith("\"") || text.length() < 2) throw error("expected a quoted string", text);
        return text.substring(1, text.length() - 1);
    }

    private static IllegalArgumentException error(String message, String line) {
        return new IllegalArgumentException("script: " + message + (line.isEmpty() ? "" : " in: " + line));
    }

    /** Tokenizer and recursive-descent parser for expressions. */
    private static final class Tokens {

        private final String text;
        private int pos;

        Tokens(String text) {
            this.text = text;
        }

        Expr expression() {
            var expr = term();
            while (peek() == '|') {
                pos++;
                var filter = identifier();
                var arg = atTermStart() ? term() : null;
                expr = new Expr.Pipe(expr, filter, arg);
            }
            return expr;
        }

        private Expr term() {
            skipSpaces();
            if (peek() == '"') return new Expr.Str(string());
            if (Character.isDigit(peek())) {
                var start = pos;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) pos++;
                return new Expr.Str(text.substring(start, pos)); // numbers are text; eq compares them as such
            }
            var name = identifier();
            if (peek() == '(') {
                pos++;
                var args = new ArrayList<Expr>();
                if (peek() != ')') {
                    args.add(expression());
                    while (peek() == ',') {
                        pos++;
                        args.add(expression());
                    }
                }
                expect(')');
                return new Expr.Fn(name, args);
            }
            return new Expr.Var(name);
        }

        private boolean atTermStart() {
            var c = peek();
            return c == '"' || Character.isDigit(c) || Character.isJavaIdentifierStart(c);
        }

        String remainingString() {
            skipSpaces();
            var s = string();
            end();
            return s;
        }

        private String string() {
            expect('"');
            var end = text.indexOf('"', pos);
            if (end < 0) throw error("unterminated string", text);
            var s = text.substring(pos, end);
            pos = end + 1;
            return s;
        }

        private String identifier() {
            skipSpaces();
            var start = pos;
            while (pos < text.length() && (Character.isJavaIdentifierPart(text.charAt(pos)) || text.charAt(pos) == '-')) pos++;
            if (start == pos) throw error("expected a name at " + pos, text);
            return text.substring(start, pos);
        }

        private void expect(char c) {
            if (peek() != c) throw error("expected '" + c + "' at " + pos, text);
            pos++;
        }

        private char peek() {
            skipSpaces();
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private void skipSpaces() {
            while (pos < text.length() && text.charAt(pos) == ' ') pos++;
        }

        void end() {
            if (peek() != '\0') throw error("unexpected text at " + pos, text);
        }
    }
}
