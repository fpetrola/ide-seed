package ide.java.semantics;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.YieldStmt;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Whether two statements of a body can run in either order: neither gives a value (a local, a field, an element of an
 * array) the other names, neither leaves the body (return, break, continue, throw), and what one does cannot change
 * what the other reads or does: of two with effects, or one with effects and one that reads the heap, the order stays.
 * A new object of the workspace whose constructor only keeps what it is given, in a class of nothing but such fields,
 * does nothing but be made. What a lambda's body does happens later, not where it is written. An exception one of them
 * may throw is not counted.
 */
public final class Order {

    private Order() {
    }

    /** What keeps {@code a} and {@code b} in their order, or null when they can run in either. */
    public static Node between(Statement a, Statement b, Predicate<MethodCallExpr> reads) {
        for (var statement : new Statement[]{a, b}) {
            var jump = statement.findFirst(Node.class, n -> (n instanceof ReturnStmt || n instanceof BreakStmt || n instanceof ContinueStmt
                    || n instanceof ThrowStmt || n instanceof YieldStmt) && !later(n, statement)).orElse(null);
            if (jump != null) return jump;
        }
        var given = given(a, b);
        if (given != null) return given;
        given = given(b, a);
        if (given != null) return given;
        var doneA = effect(a, reads);
        var doneB = effect(b, reads);
        if (doneA != null && (doneB != null || heap(b, reads) != null)) return doneA;
        if (doneB != null && heap(a, reads) != null) return doneB;
        return null;
    }

    /** What in {@code writer} gives a value to a name {@code reader} names, or null. */
    private static Node given(Statement writer, Statement reader) {
        var named = new HashSet<String>();
        reader.findAll(NameExpr.class).forEach(n -> named.add(n.getNameAsString()));
        reader.findAll(FieldAccessExpr.class).forEach(f -> named.add(f.getNameAsString()));
        reader.findAll(VariableDeclarator.class).forEach(v -> named.add(v.getNameAsString()));
        return writer.findFirst(Node.class, n -> written(n).stream().anyMatch(named::contains)).orElse(null);
    }

    /** The names a node gives a value to: a variable it declares, what it assigns or increments. */
    private static Set<String> written(Node n) {
        return switch (n) {
            case VariableDeclarator v -> Set.of(v.getNameAsString());
            case AssignExpr assign -> Set.of(name(assign.getTarget()));
            case UnaryExpr u when u.getOperator().name().endsWith("CREMENT") -> Set.of(name(u.getExpression()));
            default -> Set.of();
        };
    }

    private static String name(Expression target) {
        return switch (target) {
            case NameExpr n -> n.getNameAsString();
            case FieldAccessExpr f -> f.getNameAsString();
            case ArrayAccessExpr a -> name(a.getName());
            case com.github.javaparser.ast.expr.EnclosedExpr e -> name(e.getInner());
            default -> "";
        };
    }

    /** What in a statement does more than give a value, now, or null. */
    private static Node effect(Statement statement, Predicate<MethodCallExpr> reads) {
        return statement.findFirst(Node.class, n -> !later(n, statement) && (n instanceof MethodCallExpr call && !reads.test(call)
                || n instanceof ObjectCreationExpr creation && !kept(creation)
                || n instanceof ArrayCreationExpr || n instanceof ArrayInitializerExpr
                || n instanceof AssignExpr assign && !(assign.getTarget() instanceof NameExpr local && Resolution.resolveField(local) == null)
                || n instanceof UnaryExpr u && u.getOperator().name().endsWith("CREMENT")
                && !(u.getExpression() instanceof NameExpr local && Resolution.resolveField(local) == null))).orElse(null);
    }

    /** What in a statement reads what effects may change, now: a field, an array, what an object holds; or null. */
    private static Node heap(Statement statement, Predicate<MethodCallExpr> reads) {
        return statement.findFirst(Node.class, n -> !later(n, statement) && (n instanceof FieldAccessExpr && !(n.getParentNode().orElse(null) instanceof FieldAccessExpr)
                || n instanceof ArrayAccessExpr || n instanceof NameExpr name && Resolution.resolveField(name) != null
                || n instanceof MethodCallExpr call && reads.test(call))).orElse(null);
    }

    /** Whether a node runs later than its statement: in a lambda's body, a method reference, or a class written in it. */
    private static boolean later(Node n, Statement statement) {
        for (var at = n.getParentNode().orElse(null); at != null && at != statement; at = at.getParentNode().orElse(null)) {
            if (at instanceof LambdaExpr || at instanceof MethodReferenceExpr || at instanceof ClassOrInterfaceDeclaration
                    || at instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()) return true;
        }
        return false;
    }

    /**
     * A new object of a class of the workspace that does nothing but keep what it is given: it extends nothing, its
     * fields start with no effects, it has no initializer blocks, and the constructor called only assigns its fields
     * values it is given or literals.
     */
    private static boolean kept(ObjectCreationExpr creation) {
        if (creation.getAnonymousClassBody().isPresent()) return false;
        try {
            var type = creation.getType().resolve().asReferenceType().getTypeDeclaration().flatMap(d -> d.toAst())
                    .filter(ClassOrInterfaceDeclaration.class::isInstance).map(ClassOrInterfaceDeclaration.class::cast).orElse(null);
            if (type == null || !type.getExtendedTypes().isEmpty() || type.getMembers().stream().anyMatch(InitializerDeclaration.class::isInstance)) return false;
            for (var field : type.getFields()) {
                for (var variable : field.getVariables()) {
                    if (variable.getInitializer().filter(v -> Bodies.effect(v) != null).isPresent()) return false;
                }
            }
            if (type.getConstructors().isEmpty()) return creation.getArguments().isEmpty();
            var constructor = creation.resolve().toAst().filter(ConstructorDeclaration.class::isInstance).map(ConstructorDeclaration.class::cast).orElse(null);
            if (constructor == null) return false;
            var parameters = new HashSet<String>();
            constructor.getParameters().forEach(p -> parameters.add(p.getNameAsString()));
            return constructor.getBody().getStatements().stream().allMatch(s -> s instanceof ExpressionStmt e
                    && e.getExpression() instanceof AssignExpr assign && assign.getOperator() == AssignExpr.Operator.ASSIGN
                    && (assign.getTarget() instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr
                    || assign.getTarget() instanceof NameExpr n && !parameters.contains(n.getNameAsString()))
                    && (assign.getValue() instanceof NameExpr v && parameters.contains(v.getNameAsString()) || assign.getValue() instanceof LiteralExpr));
        } catch (RuntimeException unresolved) {
            return false;
        }
    }
}
