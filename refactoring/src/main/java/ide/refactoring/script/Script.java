package ide.refactoring.script;

import ide.core.Param;
import ide.program.Body;
import ide.program.ElementId;
import ide.projection.PrimitiveRejected;
import ide.refactoring.Args;
import ide.refactoring.Refactoring;
import ide.refactoring.Run;
import ide.refactoring.Value;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** A refactoring written in the {@code .ref} language, over typed values (see {@link Value}). */
public final class Script implements Refactoring {

    private static final Pattern INTERPOLATION = Pattern.compile("([$#])\\{([^}]*)}");

    private final String id;
    private final String description;
    private final List<Param> params;
    private final List<Stmt> body;

    /** An anonymous script: a block of statements run with the given variables, as knowledge effects are. */
    public static Script block(String statements, List<String> variables) {
        var header = new StringBuilder("refactoring block\n");
        variables.forEach(v -> header.append("  param ").append(v).append(" : text\n"));
        return ScriptParser.parse(header + statements + "\nend\n").getFirst();
    }

    /** Evaluates an expression with the given variables. */
    public static Value evaluate(String expression, Run run, Map<String, Value> variables) {
        return new Script("expression", "", List.of(), List.of()).eval(ScriptParser.expression(expression), run, variables);
    }

    /** Interpolates {@code ${expr}} and {@code #{expr}} in a text with the given variables. */
    public static String render(String text, Run run, Map<String, Value> variables) {
        return Value.asText(new Script("text", "", List.of(), List.of()).interpolate(text, run, variables));
    }

    Script(String id, String description, List<Param> params, List<Stmt> body) {
        this.id = id;
        this.description = description;
        this.params = List.copyOf(params);
        this.body = List.copyOf(body);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public List<Param> params() {
        return params;
    }

    @Override
    public String level() {
        return "script";
    }

    /** Number of statements, nested ones included. */
    public int size() {
        return count(body);
    }

    @Override
    public ElementId apply(Run run, Args args) {
        var env = new HashMap<String, Value>();
        var graph = run.program();
        for (var param : params) {
            var value = switch (param.type()) {
                case "element" -> new Value.Elem(args.element(param.name(), graph).id());
                case "elements" -> new Value.Items(args.elements(param.name(), graph).stream()
                        .<Value>map(e -> new Value.Elem(e.id())).toList());
                default -> args.value(param.name());
            };
            env.put(param.name(), value);
        }
        try {
            run(body, run, env);
            return null;
        } catch (Returned returned) {
            if (returned.value == null || returned.value instanceof Value.Elem) {
                return returned.value == null ? null : ((Value.Elem) returned.value).id();
            }
            throw new IllegalArgumentException("script " + id + " returns no element: " + returned.value.text());
        }
    }

    private void run(List<Stmt> statements, Run run, Map<String, Value> env) {
        for (var statement : statements) {
            switch (statement) {
                case Stmt.Let let -> env.put(let.variable(), eval(let.value(), run, env));
                case Stmt.Call call -> {
                    var result = run.call(call.refactoring(), bind(call, run, env));
                    if (call.variable() != null) env.put(call.variable(), result == null ? null : new Value.Elem(result));
                }
                case Stmt.For loop -> {
                    for (var item : Value.items(eval(loop.items(), run, env))) {
                        env.put(loop.variable(), item);
                        run(loop.body(), run, env);
                    }
                }
                case Stmt.If branch ->
                        run(Value.truthy(eval(branch.condition(), run, env)) ? branch.then() : branch.otherwise(), run, env);
                case Stmt.Try attempt -> run.attempt(text(attempt.label(), run, env), () -> run(attempt.body(), run, env));
                case Stmt.Require require -> {
                    if (!Value.truthy(eval(require.condition(), run, env))) {
                        throw new PrimitiveRejected("refactoring.precondition", text(require.message(), run, env));
                    }
                }
                case Stmt.Oblige oblige -> run.oblige(text(oblige.assumption(), run, env));
                case Stmt.Fail fail -> throw new PrimitiveRejected("refactoring.failed", text(fail.message(), run, env));
                case Stmt.Return ret -> throw new Returned(interpolate(ret.value(), run, env));
            }
        }
    }

    /** Positional arguments follow the callee's declared parameters; {@code name=value} binds by name. */
    private Map<String, Value> bind(Stmt.Call call, Run run, Map<String, Value> env) {
        var declared = run.params(call.refactoring());
        var bound = new LinkedHashMap<String, Value>();
        var position = 0;
        for (var raw : call.args()) {
            var eq = raw.indexOf('=');
            if (eq > 0 && !raw.startsWith("${") && raw.substring(0, eq).chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')) {
                bound.put(raw.substring(0, eq), interpolate(raw.substring(eq + 1), run, env));
            } else {
                if (position >= declared.size()) throw new IllegalArgumentException("too many arguments for " + call.refactoring());
                bound.put(declared.get(position++).name(), interpolate(raw, run, env));
            }
        }
        return bound;
    }

    /**
     * Text with {@code ${expr}}, a value as text, and {@code #{expr}}, elements as a body names them ({@link Body}), a
     * list joined by commas. An argument that is exactly one {@code ${expr}} keeps the value (e.g. a list).
     */
    private Value interpolate(String text, Run run, Map<String, Value> env) {
        var whole = INTERPOLATION.matcher(text);
        if (whole.matches() && whole.group(1).equals("$")) return eval(ScriptParser.expression(whole.group(2)), run, env);
        return new Value.Text(INTERPOLATION.matcher(text).replaceAll(m -> {
            var value = eval(ScriptParser.expression(m.group(2)), run, env);
            return Matcher.quoteReplacement(m.group(1).equals("$") ? Value.asText(value) : named(value));
        }));
    }

    private static String named(Value value) {
        return Value.items(value).stream().map(item -> {
            if (!(item instanceof Value.Elem element)) throw new IllegalArgumentException("a body names elements, not " + Value.asText(item));
            return Body.name(element.id());
        }).collect(Collectors.joining(", "));
    }

    private String text(String text, Run run, Map<String, Value> env) {
        return Value.asText(interpolate(text, run, env));
    }

    private Value eval(Expr expr, Run run, Map<String, Value> env) {
        var functions = new ScriptFunctions(run.program(), run.rules());
        return switch (expr) {
            case Expr.Str s -> new Value.Text(s.value());
            case Expr.Var v -> {
                if (!env.containsKey(v.name())) throw new IllegalArgumentException("script " + id + ": unknown variable " + v.name());
                yield env.get(v.name());
            }
            case Expr.Fn f -> functions.call(f.name(), f.args().stream().map(a -> eval(a, run, env)).toList());
            case Expr.Pipe p -> {
                var arg = p.arg() == null ? null : eval(p.arg(), run, env);
                yield new Value.Items(Value.items(eval(p.source(), run, env)).stream()
                        .filter(item -> functions.filter(p.filter(), item, arg)).toList());
            }
        };
    }

    private static int count(List<Stmt> statements) {
        var n = 0;
        for (var s : statements) {
            n++;
            if (s instanceof Stmt.For f) n += count(f.body());
            if (s instanceof Stmt.If i) n += count(i.then()) + count(i.otherwise());
            if (s instanceof Stmt.Try t) n += count(t.body());
        }
        return n;
    }

    private static final class Returned extends RuntimeException {
        final Value value;

        Returned(Value value) {
            super(null, null, false, false);
            this.value = value;
        }
    }
}
