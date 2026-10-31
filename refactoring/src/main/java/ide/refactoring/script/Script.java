package ide.refactoring.script;

import ide.core.Param;
import ide.projection.PrimitiveRejected;
import ide.refactoring.Args;
import ide.refactoring.Ctx;
import ide.refactoring.Refactoring;

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
    public static Object evaluate(String expression, Ctx ctx, Map<String, Object> variables) {
        return new Script("expression", "", List.of(), List.of()).eval(ScriptParser.expression(expression), ctx, variables);
    }

    /** Interpolates {@code ${expr}} in a text with the given variables. */
    public static String render(String text, Ctx ctx, Map<String, Object> variables) {
        return String.valueOf(new Script("text", "", List.of(), List.of()).interpolate(text, ctx, variables));
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
    public String apply(Ctx ctx, Args args) {
        var env = new HashMap<String, Object>();
        var graph = ctx.projection().graph();
        for (var param : params) {
            Object value = switch (param.type()) {
                case "element" -> args.element(param.name(), graph).id().value();
                case "elements" -> args.elements(param.name(), graph).stream().map(e -> e.id().value()).toList();
                default -> args.values().get(param.name());
            };
            env.put(param.name(), value);
        }
        try {
            run(body, ctx, env);
            return null;
        } catch (Returned returned) {
            return returned.value;
        }
    }

    private void run(List<Stmt> statements, Ctx ctx, Map<String, Object> env) {
        for (var statement : statements) {
            switch (statement) {
                case Stmt.Let let -> env.put(let.variable(), eval(let.value(), ctx, env));
                case Stmt.Call call -> {
                    var result = ctx.call(call.refactoring(), bind(call, ctx, env));
                    if (call.variable() != null) env.put(call.variable(), result);
                }
                case Stmt.For loop -> {
                    for (var item : ScriptFunctions.list(eval(loop.items(), ctx, env))) {
                        env.put(loop.variable(), item);
                        run(loop.body(), ctx, env);
                    }
                }
                case Stmt.If branch ->
                        run(ScriptFunctions.truthy(eval(branch.condition(), ctx, env)) ? branch.then() : branch.otherwise(), ctx, env);
                case Stmt.Try attempt -> ctx.attempt(interpolate(attempt.label(), ctx, env).toString(),
                        () -> run(attempt.body(), ctx, env));
                case Stmt.Require require -> {
                    if (!ScriptFunctions.truthy(eval(require.condition(), ctx, env))) {
                        throw new PrimitiveRejected("refactoring.precondition", interpolate(require.message(), ctx, env).toString());
                    }
                }
                case Stmt.Oblige oblige -> ctx.oblige(interpolate(oblige.assumption(), ctx, env).toString());
                case Stmt.Fail fail -> throw new PrimitiveRejected("refactoring.failed", interpolate(fail.message(), ctx, env).toString());
                case Stmt.Return ret -> {
                    var value = interpolate(ret.value(), ctx, env);
                    throw new Returned(value == null ? null : value.toString());
                }
            }
        }
    }

    /** Positional arguments follow the callee's declared parameters; {@code name=value} binds by name. */
    private Map<String, Object> bind(Stmt.Call call, Ctx ctx, Map<String, Object> env) {
        var declared = ctx.params(call.refactoring());
        var bound = new LinkedHashMap<String, Object>();
        var position = 0;
        for (var raw : call.args()) {
            var eq = raw.indexOf('=');
            if (eq > 0 && !raw.startsWith("${") && raw.substring(0, eq).chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')) {
                bound.put(raw.substring(0, eq), interpolate(raw.substring(eq + 1), ctx, env));
            } else {
                if (position >= declared.size()) throw new IllegalArgumentException("too many arguments for " + call.refactoring());
                bound.put(declared.get(position++).name(), interpolate(raw, ctx, env));
            }
        }
        return bound;
    }

    /** Text with {@code ${expr}}; an argument that is exactly one {@code ${expr}} keeps the value (e.g. a list). */
    Object interpolate(String text, Ctx ctx, Map<String, Object> env) {
        var whole = INTERPOLATION.matcher(text);
        if (whole.matches()) return eval(ScriptParser.expression(whole.group(1)), ctx, env);
        return INTERPOLATION.matcher(text).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(String.valueOf(eval(ScriptParser.expression(m.group(1)), ctx, env))));
    }

    Object eval(Expr expr, Ctx ctx, Map<String, Object> env) {
        var functions = new ScriptFunctions(ctx.projection().graph(), ctx.rules());
        return switch (expr) {
            case Expr.Str s -> s.value();
            case Expr.Var v -> {
                if (!env.containsKey(v.name())) throw new IllegalArgumentException("script " + id + ": unknown variable " + v.name());
                yield env.get(v.name());
            }
            case Expr.Fn f -> functions.call(f.name(), f.args().stream().map(a -> eval(a, ctx, env)).toList());
            case Expr.Pipe p -> {
                var items = ScriptFunctions.list(eval(p.source(), ctx, env));
                var arg = p.arg() == null ? null : eval(p.arg(), ctx, env);
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
