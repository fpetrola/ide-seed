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

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
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

/** Expression-level helpers for the body primitives (inline, extract): purity, precedence, parentheses, locals. */
public final class Bodies {

    private Bodies() {
    }

    /** The statements of a method's or constructor's body, in order; none if it has no body. */
    public static List<Statement> statements(CallableDeclaration<?> callable) {
        var body = body(callable);
        return body == null ? List.of() : body.getStatements();
    }

    /** A method's or constructor's body; null for a method without one. */
    public static BlockStmt body(CallableDeclaration<?> callable) {
        return callable instanceof MethodDeclaration method ? method.getBody().orElse(null)
                : ((com.github.javaparser.ast.body.ConstructorDeclaration) callable).getBody();
    }

    /** Where a statement is among its body's, by what it is and not by how it looks; -1 if it is none of them. */
    public static int place(List<Statement> statements, Node statement) {
        for (var i = 0; i < statements.size(); i++) if (statements.get(i) == statement) return i;
        return -1;
    }

    /**
     * The variables a method or constructor declares in its own body, in the order they are written; those of a class
     * or a lambda inside it are theirs.
     */
    public static List<VariableDeclarator> locals(CallableDeclaration<?> callable) {
        return callable.findAll(VariableDeclarator.class, v -> v.getParentNode().filter(VariableDeclarationExpr.class::isInstance).isPresent()
                && v.findAncestor(CallableDeclaration.class).orElse(null) == callable
                && v.findAncestor(LambdaExpr.class).map(lambda -> lambda.findAncestor(CallableDeclaration.class).orElse(null) != callable).orElse(true));
    }

    /**
     * What in an expression does more than give a value, or null: a call, a new object (a lambda or a method reference
     * too, whose type also comes from where it is written), an assignment, an increment.
     */
    public static Node effect(Expression e) {
        return e.findFirst(Node.class, n -> n instanceof MethodCallExpr || n instanceof ObjectCreationExpr
                || n instanceof ArrayCreationExpr || n instanceof ArrayInitializerExpr || n instanceof LambdaExpr
                || n instanceof MethodReferenceExpr || n instanceof AssignExpr
                || n instanceof UnaryExpr u && u.getOperator().name().endsWith("CREMENT")).orElse(null);
    }

    /**
     * What keeps a value with effects from moving, from {@code declaration} (alone in its statement), to {@code use}, or
     * null when it runs there at the same point among what else runs: the use is in a statement of the declaration's
     * block, read whatever happens (not on a branch, in a loop or a lambda), and what runs between them, in the
     * statements between and in the use's own before it, has no effects and reads only locals.
     */
    public static Node movedPast(VariableDeclarator declaration, Expression use) {
        var declared = declaration.getParentNode().filter(d -> d instanceof VariableDeclarationExpr v && v.getVariables().size() == 1)
                .flatMap(Node::getParentNode).filter(ExpressionStmt.class::isInstance).orElse(null);
        if (declared == null || !(declared.getParentNode().orElse(null) instanceof BlockStmt block)) return declaration;
        var branch = branch(block, use);
        if (branch != null) return branch;
        Node at = use;
        while (at.getParentNode().orElseThrow() != block) at = at.getParentNode().orElseThrow();
        var statements = block.getStatements();
        for (var i = statements.indexOf((Statement) declared) + 1; i < statements.indexOf((Statement) at); i++) {
            var busy = busy(statements.get(i), null);
            if (busy != null) return busy;
        }
        return busy(at, use);
    }

    /**
     * What makes {@code use} run other than once each time a statement of {@code block} runs, or null: a branch, a loop,
     * a lambda, a statement of a block nested in it; {@code use} itself when it is not in the block.
     */
    public static Node branch(BlockStmt block, Expression use) {
        for (var parent = use.getParentNode().orElse(null); parent != block; parent = parent.getParentNode().orElse(null)) {
            if (parent == null) return use;
            var conditional = parent instanceof ConditionalExpr || parent instanceof LambdaExpr || parent instanceof SwitchExpr
                    || parent instanceof BinaryExpr b && (b.getOperator() == BinaryExpr.Operator.AND || b.getOperator() == BinaryExpr.Operator.OR)
                    || parent instanceof Statement && parent.getParentNode().orElse(null) != block;
            if (conditional) return parent;
        }
        return null;
    }

    /** What in {@code node}, before {@code until} if given (and not around it), does something or reads the heap, or null. */
    private static Node busy(Node node, Node until) {
        for (var n : node.findAll(Node.class)) {
            if (until != null && (n.isAncestorOf(until) || n == until || !n.getEnd().orElseThrow().isBefore(until.getBegin().orElseThrow()))) continue;
            if (n instanceof Expression e && effect(e) != null) return effect(e);
            if (n instanceof FieldAccessExpr || n instanceof ArrayAccessExpr || n instanceof NameExpr name && Resolution.resolveField(name) != null) return n;
        }
        return null;
    }

    /**
     * What may change what {@code value} reads between where it is given, at the end of {@code declaration}, and where
     * {@code use} reads it, or null: a write of a name it reads, or, if it reads a field or an array, a call or a new
     * object. A use in a loop that the declaration is not in is read again in the turns that follow: the rest of the
     * loop counts too.
     */
    public static Node changedBetween(Node declaration, Expression value, Node use) {
        var start = declaration.getEnd().orElseThrow();
        var end = use.getBegin().orElseThrow();
        for (var n = use.getParentNode().orElse(null); n != null && !n.isAncestorOf(declaration); n = n.getParentNode().orElse(null)) {
            if (n instanceof ForStmt || n instanceof ForEachStmt || n instanceof WhileStmt || n instanceof DoStmt) end = n.getEnd().orElseThrow();
        }
        var read = new java.util.HashSet<String>();
        value.findAll(NameExpr.class).forEach(n -> read.add(n.getNameAsString()));
        value.findAll(FieldAccessExpr.class).forEach(f -> read.add(f.getNameAsString()));
        var heap = !value.findAll(FieldAccessExpr.class).isEmpty() || !value.findAll(ArrayAccessExpr.class).isEmpty()
                || value.findAll(NameExpr.class).stream().anyMatch(n -> Resolution.resolveField(n) != null);
        var stop = end;
        var callable = use.findAncestor(CallableDeclaration.class).orElseThrow();
        return callable.findFirst(Node.class, n -> n.getBegin().filter(b -> b.isAfter(start) && b.isBefore(stop)).isPresent()
                && (n instanceof AssignExpr a && read.contains(written(a.getTarget()))
                || n instanceof UnaryExpr u && u.getOperator().name().endsWith("CREMENT") && read.contains(written(u.getExpression()))
                || heap && (n instanceof MethodCallExpr || n instanceof ObjectCreationExpr))).orElse(null);
    }

    /** The name a write changes: the variable, the field, or the array whose element it is. */
    private static String written(Expression target) {
        return switch (target) {
            case NameExpr n -> n.getNameAsString();
            case FieldAccessExpr f -> f.getNameAsString();
            case ArrayAccessExpr a -> written(a.getName());
            case EnclosedExpr e -> written(e.getInner());
            default -> "";
        };
    }

    /** What reads {@code use} later than where it is written, or null: a lambda or a class inside the declaration's method. */
    public static Node deferred(Node declaration, Node use) {
        for (var n = use.getParentNode().orElse(null); n != null && !n.isAncestorOf(declaration); n = n.getParentNode().orElse(null)) {
            if (n instanceof LambdaExpr || n instanceof ClassOrInterfaceDeclaration
                    || n instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) return n;
        }
        return null;
    }

    /** Which of its name a local is among {@code locals}, 0 the first; -1 if it is not one of them. */
    public static int ordinal(List<VariableDeclarator> locals, VariableDeclarator local) {
        var ordinal = 0;
        for (var other : locals) {
            if (other == local) return ordinal; // nodes are equal by shape: two alike declarations are still two
            if (other.getNameAsString().equals(local.getNameAsString())) ordinal++;
        }
        return -1;
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
        var declared = new java.util.ArrayList<VariableDeclarator>();
        fragment.forEach(s -> declared.addAll(s.findAll(VariableDeclarator.class)));
        var usedAfter = new java.util.HashSet<String>();
        method.findAll(NameExpr.class).stream().filter(n -> n.getBegin().orElseThrow().isAfter(end))
                .filter(n -> !inside.containsKey(n.getNameAsString()) || bindsOneOf(n, declared))
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

    /**
     * Whether a name binds one of {@code declared}: another local of the same name, in a block of its own, does not.
     * What the solver cannot tell is taken to bind it.
     */
    private static boolean bindsOneOf(NameExpr name, List<VariableDeclarator> declared) {
        var local = Resolution.resolveLocal(name);
        return local == null || declared.stream().anyMatch(v -> v == local);
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
