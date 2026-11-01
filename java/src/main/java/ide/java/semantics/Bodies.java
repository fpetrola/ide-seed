package ide.java.semantics;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Expression-level helpers for the body primitives (inline, extract): purity, precedence, parentheses. */
public final class Bodies {

    private Bodies() {
    }

    /** Evaluating it twice, or not at all, cannot be told apart: names, literals, this, and field reads of those. */
    public static boolean trivial(Expression e) {
        return e instanceof NameExpr || e instanceof ThisExpr || e instanceof IntegerLiteralExpr || e instanceof LongLiteralExpr
                || e instanceof DoubleLiteralExpr || e instanceof BooleanLiteralExpr || e instanceof CharLiteralExpr
                || e instanceof StringLiteralExpr || e instanceof NullLiteralExpr
                || e instanceof FieldAccessExpr f && trivial(f.getScope());
    }

    /** Whether {@code child}, placed where {@code slot} is, needs parentheses to keep its meaning. */
    public static boolean needsParentheses(Expression child, Node slot) {
        if (!(child instanceof BinaryExpr inner)) return false;
        var parent = slot.getParentNode().orElse(null);
        if (parent instanceof UnaryExpr) return true;
        if (!(parent instanceof BinaryExpr outer)) return false;
        var difference = PRECEDENCE.get(inner.getOperator()) - PRECEDENCE.get(outer.getOperator());
        if (difference != 0) return difference < 0;
        return outer.getRight() == slot; // a - (b - c): same level on the right changes the grouping
    }

    public static Expression parenthesized(Expression e) {
        return new EnclosedExpr(e);
    }

    public static boolean primary(Expression e) {
        return !(e instanceof BinaryExpr) && !(e instanceof UnaryExpr) && !(e instanceof com.github.javaparser.ast.expr.ConditionalExpr)
                && !(e instanceof com.github.javaparser.ast.expr.AssignExpr) && !(e instanceof com.github.javaparser.ast.expr.CastExpr)
                && !(e instanceof com.github.javaparser.ast.expr.InstanceOfExpr) && !(e instanceof com.github.javaparser.ast.expr.LambdaExpr)
                || e instanceof MethodCallExpr;
    }

    /** The statements of the outermost block of {@code method} that lie whole within lines first..last. */
    public static List<Statement> statementsWithin(MethodDeclaration method, int first, int last) {
        for (var block : method.findAll(BlockStmt.class)) {
            var inside = block.getStatements().stream().filter(s -> s.getBegin().orElseThrow().line >= first
                    && s.getEnd().orElseThrow().line <= last).toList();
            var partial = block.getStatements().stream().anyMatch(s -> !inside.contains(s)
                    && s.getBegin().orElseThrow().line <= last && s.getEnd().orElseThrow().line >= first);
            if (!inside.isEmpty() && !partial) return inside;
        }
        return List.of();
    }

    /** A return, or a break/continue whose loop or switch is outside the fragment; null if none. */
    public static Statement jumpOut(List<Statement> fragment) {
        return jumpOut(fragment, false);
    }

    /** The first statement that leaves the fragment; a return does not when {@code returning}: the method's own end. */
    public static Statement jumpOut(List<Statement> fragment, boolean returning) {
        for (var statement : fragment) {
            for (var jump : statement.findAll(Statement.class, s -> s instanceof ReturnStmt || s instanceof BreakStmt || s instanceof ContinueStmt)) {
                if (jump.findAncestor(LambdaExpr.class).filter(l -> statement.isAncestorOf(l)).isPresent()) continue;
                if (jump instanceof ReturnStmt) {
                    if (returning) continue;
                    return jump;
                }
                var target = jump.findAncestor(Statement.class, s -> s instanceof ForStmt || s instanceof ForEachStmt
                        || s instanceof WhileStmt || s instanceof DoStmt || (jump instanceof BreakStmt && s instanceof SwitchStmt));
                if (target.isEmpty() || !(statement == target.get() || statement.isAncestorOf(target.get()))) return jump;
            }
        }
        return null;
    }

    /** Data flow of a fragment: locals it reads from before (inputs) and values it sets that are read after. */
    public record Flow(Map<String, Type> inputs, Map<String, Type> outputs, java.util.Set<String> inside) {
        public boolean declaredInside(String name) {
            return inside.contains(name);
        }
    }

    /**
     * The locals and parameters an expression reads, declared before it, by name and in the order it reads them; the
     * parameters of a lambda inside it are its own.
     */
    public static Map<String, Type> inputs(MethodDeclaration method, com.github.javaparser.ast.expr.Expression expression) {
        var start = expression.getBegin().orElseThrow();
        var before = new LinkedHashMap<String, Type>();
        method.getParameters().forEach(p -> before.put(p.getNameAsString(), p.getType()));
        for (var v : method.findAll(VariableDeclarator.class)) {
            if (v.getEnd().orElseThrow().isBefore(start)) before.put(v.getNameAsString(), v.getType());
        }
        var own = expression.findAll(com.github.javaparser.ast.body.Parameter.class).stream().map(p -> p.getNameAsString()).toList();
        var inputs = new LinkedHashMap<String, Type>();
        var names = new java.util.ArrayList<>(expression.findAll(NameExpr.class));
        for (var name : names) {
            var n = name.getNameAsString();
            if (before.containsKey(n) && !own.contains(n)) inputs.putIfAbsent(n, before.get(n));
        }
        return inputs;
    }

    public static Flow flow(MethodDeclaration method, List<Statement> fragment) {
        var start = fragment.getFirst().getBegin().orElseThrow();
        var end = fragment.getLast().getEnd().orElseThrow();
        var before = new LinkedHashMap<String, Type>();
        method.getParameters().forEach(p -> before.put(p.getNameAsString(), p.getType()));
        for (var v : method.findAll(VariableDeclarator.class)) {
            if (v.getEnd().orElseThrow().isBefore(start)) before.put(v.getNameAsString(), v.getType());
        }
        var inside = new LinkedHashMap<String, Type>();
        fragment.forEach(s -> s.findAll(VariableDeclarator.class).forEach(v -> inside.put(v.getNameAsString(), v.getType())));
        var inputs = new LinkedHashMap<String, Type>();
        var assigned = new java.util.LinkedHashSet<String>();
        for (var statement : fragment) {
            for (var name : statement.findAll(NameExpr.class)) {
                var n = name.getNameAsString();
                if (before.containsKey(n) && !inside.containsKey(n)) inputs.putIfAbsent(n, before.get(n));
            }
            statement.findAll(AssignExpr.class).forEach(a -> {
                if (a.getTarget() instanceof NameExpr target) assigned.add(target.getNameAsString());
            });
            statement.findAll(UnaryExpr.class).forEach(u -> {
                if (u.getExpression() instanceof NameExpr target) assigned.add(target.getNameAsString());
            });
        }
        var usedAfter = new java.util.HashSet<String>();
        method.findAll(NameExpr.class).stream().filter(n -> n.getBegin().orElseThrow().isAfter(end))
                .forEach(n -> usedAfter.add(n.getNameAsString()));
        var outputs = new LinkedHashMap<String, Type>();
        inside.forEach((n, t) -> {
            if (usedAfter.contains(n)) outputs.put(n, t);
        });
        assigned.forEach(n -> {
            if (before.containsKey(n) && !inside.containsKey(n) && usedAfter.contains(n)) outputs.put(n, before.get(n));
        });
        return new Flow(inputs, outputs, inside.keySet());
    }

    /** Shifts lines so the least indented one starts with {@code indent}. */
    public static String reindent(String text, String indent) {
        var lines = text.lines().toList();
        var least = lines.stream().filter(l -> !l.isBlank()).mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
        var out = new StringBuilder();
        for (var line : lines) out.append(line.isBlank() ? "" : indent + line.substring(least)).append('\n');
        return out.toString();
    }

    private static final Map<BinaryExpr.Operator, Integer> PRECEDENCE = Map.ofEntries(
            Map.entry(BinaryExpr.Operator.OR, 1), Map.entry(BinaryExpr.Operator.AND, 2),
            Map.entry(BinaryExpr.Operator.BINARY_OR, 3), Map.entry(BinaryExpr.Operator.XOR, 4),
            Map.entry(BinaryExpr.Operator.BINARY_AND, 5),
            Map.entry(BinaryExpr.Operator.EQUALS, 6), Map.entry(BinaryExpr.Operator.NOT_EQUALS, 6),
            Map.entry(BinaryExpr.Operator.LESS, 7), Map.entry(BinaryExpr.Operator.GREATER, 7),
            Map.entry(BinaryExpr.Operator.LESS_EQUALS, 7), Map.entry(BinaryExpr.Operator.GREATER_EQUALS, 7),
            Map.entry(BinaryExpr.Operator.LEFT_SHIFT, 8), Map.entry(BinaryExpr.Operator.SIGNED_RIGHT_SHIFT, 8),
            Map.entry(BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT, 8),
            Map.entry(BinaryExpr.Operator.PLUS, 9), Map.entry(BinaryExpr.Operator.MINUS, 9),
            Map.entry(BinaryExpr.Operator.MULTIPLY, 10), Map.entry(BinaryExpr.Operator.DIVIDE, 10),
            Map.entry(BinaryExpr.Operator.REMAINDER, 10));
}
