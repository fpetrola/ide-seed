package ide.java;

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

import java.util.Map;

/** Expression-level helpers for the body primitives (inline, extract): purity, precedence, parentheses. */
final class Bodies {

    private Bodies() {
    }

    /** Evaluating it twice, or not at all, cannot be told apart: names, literals, this, and field reads of those. */
    static boolean trivial(Expression e) {
        return e instanceof NameExpr || e instanceof ThisExpr || e instanceof IntegerLiteralExpr || e instanceof LongLiteralExpr
                || e instanceof DoubleLiteralExpr || e instanceof BooleanLiteralExpr || e instanceof CharLiteralExpr
                || e instanceof StringLiteralExpr || e instanceof NullLiteralExpr
                || e instanceof FieldAccessExpr f && trivial(f.getScope());
    }

    /** Whether {@code child}, placed where {@code slot} is, needs parentheses to keep its meaning. */
    static boolean needsParentheses(Expression child, Node slot) {
        if (!(child instanceof BinaryExpr inner)) return false;
        var parent = slot.getParentNode().orElse(null);
        if (parent instanceof UnaryExpr) return true;
        if (!(parent instanceof BinaryExpr outer)) return false;
        var difference = PRECEDENCE.get(inner.getOperator()) - PRECEDENCE.get(outer.getOperator());
        if (difference != 0) return difference < 0;
        return outer.getRight() == slot; // a - (b - c): same level on the right changes the grouping
    }

    static Expression parenthesized(Expression e) {
        return new EnclosedExpr(e);
    }

    static boolean primary(Expression e) {
        return !(e instanceof BinaryExpr) && !(e instanceof UnaryExpr) && !(e instanceof com.github.javaparser.ast.expr.ConditionalExpr)
                && !(e instanceof com.github.javaparser.ast.expr.AssignExpr) && !(e instanceof com.github.javaparser.ast.expr.CastExpr)
                && !(e instanceof com.github.javaparser.ast.expr.InstanceOfExpr) && !(e instanceof com.github.javaparser.ast.expr.LambdaExpr)
                || e instanceof MethodCallExpr;
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
