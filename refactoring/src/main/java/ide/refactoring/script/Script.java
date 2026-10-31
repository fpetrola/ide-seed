package ide.refactoring.script;

import ide.core.Param;
import ide.projection.PrimitiveRejected;
import ide.refactoring.Args;
import ide.refactoring.Refactoring;
import ide.refactoring.Run;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A refactoring written in the {@code .ref} language; element values are element ids. */
public final class Script implements Refactoring {

    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{([^}]*)}");

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
    public static Object evaluate(String expression, Run run, Map<String, Object> variables) {
        return new Script("expression", "", List.of(), List.of()).eval(ScriptParser.expression(expression), run, variables);
    }

    /** Interpolates {@code ${expr}} in a text with the given variables. */
    public static String render(String text, Run run, Map<String, Object> variables) {
        return String.valueOf(new Script("text", "", List.of(), List.of()).interpolate(text, run, variables));
    }

    public static boolean truthy(Object value) {
        return ScriptFunctions.truthy(value);
    }

    public static List<?> list(Object value) {
        return ScriptFunctions.list(value);
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
    public String apply(Run run, Args args) {
        var env = new HashMap<String, Object>();
        var graph = run.program();
        for (var param : params) {
            Object value = switch (param.type()) {
                case "element" -> args.element(param.name(), graph).id().value();
                case "elements" -> args.elements(param.name(), graph).stream().map(e -> e.id().value()).toList();
                default -> args.values().get(param.name());
            };
            env.put(param.name(), value);
        }
        try {
            run(body, run, env);
            return null;
        } catch (Returned returned) {
            return returned.value;
        }
    }

    private void run(List<Stmt> statements, Run run, Map<String, Object> env) {
        for (var statement : statements) {
            switch (statement) {
                case Stmt.Let let -> env.put(let.variable(), eval(let.value(), run, env));
                case Stmt.Call call -> {
                    var result = run.call(call.refactoring(), bind(call, run, env));
                    if (call.variable() != null) env.put(call.variable(), result);
                }
                case Stmt.For loop -> {
                    for (var item : ScriptFunctions.list(eval(loop.items(), run, env))) {
                        env.put(loop.variable(), item);
                        run(loop.body(), run, env);
                    }
                }
                case Stmt.If branch ->
                        run(ScriptFunctions.truthy(eval(branch.condition(), run, env)) ? branch.then() : branch.otherwise(), run, env);
                case Stmt.Try attempt -> run.attempt(interpolate(attempt.label(), run, env).toString(),
                        () -> run(attempt.body(), run, env));
                case Stmt.Require require -> {
                    if (!ScriptFunctions.truthy(eval(require.condition(), run, env))) {
                        throw new PrimitiveRejected("refactoring.precondition", interpolate(require.message(), run, env).toString());
                    }
                }
                case Stmt.Oblige oblige -> run.oblige(interpolate(oblige.assumption(), run, env).toString());
                case Stmt.Fail fail -> throw new PrimitiveRejected("refactoring.failed", interpolate(fail.message(), run, env).toString());
                case Stmt.Return ret -> {
                    var value = interpolate(ret.value(), run, env);
                    throw new Returned(value == null ? null : value.toString());
                }
            }
        }
    }

    /** Positional arguments follow the callee's declared parameters; {@code name=value} binds by name. */
    private Map<String, Object> bind(Stmt.Call call, Run run, Map<String, Object> env) {
        var declared = run.params(call.refactoring());
        var bound = new LinkedHashMap<String, Object>();
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

    /** Text with {@code ${expr}}; an argument that is exactly one {@code ${expr}} keeps the value (e.g. a list). */
    Object interpolate(String text, Run run, Map<String, Object> env) {
        var whole = INTERPOLATION.matcher(text);
        if (whole.matches()) return eval(ScriptParser.expression(whole.group(1)), run, env);
        return INTERPOLATION.matcher(text).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(String.valueOf(eval(ScriptParser.expression(m.group(1)), run, env))));
    }

    Object eval(Expr expr, Run run, Map<String, Object> env) {
        var functions = new ScriptFunctions(run.program(), run.rules());
        return switch (expr) {
            case Expr.Str s -> s.value();
            case Expr.Var v -> {
                if (!env.containsKey(v.name())) throw new IllegalArgumentException("script " + id + ": unknown variable " + v.name());
                yield env.get(v.name());
            }
            case Expr.Fn f -> functions.call(f.name(), f.args().stream().map(a -> eval(a, run, env)).toList());
            case Expr.Pipe p -> {
                var items = ScriptFunctions.list(eval(p.source(), run, env));
                var arg = p.arg() == null ? null : eval(p.arg(), run, env);
                yield items.stream().filter(item -> functions.filter(p.filter(), item, arg)).toList();
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
        final String value;

        Returned(String value) {
            super(null, null, false, false);
            this.value = value;
        }
    }
}
