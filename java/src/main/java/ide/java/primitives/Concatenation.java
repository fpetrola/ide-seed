package ide.java.primitives;

import com.github.javaparser.Position;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import ide.java.text.Unit;
import ide.projection.PrimitiveRejected;

import java.util.ArrayList;
import java.util.List;

/**
 * A stretch over operands next to each other of a String concatenation, which {@code +} groups from the left, made an
 * expression of its own, as IntelliJ's: they are put in parentheses, and a literal the stretch begins or ends inside is
 * split there ({@code ",'address of " + name} → {@code ",'" + ("address of " + name)}). The value is the same only
 * when what comes before is already a String and the group's first or second operand is one.
 */
final class Concatenation {

    private Concatenation() {
    }

    /** Regroups so that {@code begin-end} is one expression, and answers where its parentheses begin; null if it cannot. */
    static Position regroup(Context c, Unit unit, MethodDeclaration method, Position begin, Position end) {
        var chain = method.findAll(BinaryExpr.class, b -> b.getOperator() == BinaryExpr.Operator.PLUS
                        && b.getRange().filter(r -> r.contains(begin) && r.contains(end)).isPresent()
                        && !(b.getParentNode().orElse(null) instanceof BinaryExpr p && p.getLeft() == b && p.getOperator() == BinaryExpr.Operator.PLUS))
                .stream().reduce((a, b) -> a.getRange().orElseThrow().contains(b.getRange().orElseThrow()) ? b : a).orElse(null);
        if (chain == null) return null;
        var operands = operands(chain);
        var first = at(operands, begin);
        var last = at(operands, end);
        if (first < 0 || last < 0) return null;
        var from = operands.get(first);
        var to = operands.get(last);
        var splitFrom = !from.getBegin().orElseThrow().equals(begin);
        var splitTo = !to.getEnd().orElseThrow().equals(end);
        if (splitFrom && !inside(from, begin, true) || splitTo && !inside(to, end, false)) return null;
        if (!splitFrom && !string(operands.get(first)) && (first == last || !string(operands.get(first + 1)))) {
            throw new PrimitiveRejected("extract.fragment", "'" + unit.textBetween(begin, end) + "' does not concatenate Strings from its start");
        }
        var fromText = unit.textOf(from);
        var toText = unit.textOf(to);
        var kept = begin.column - from.getBegin().orElseThrow().column; // of the first literal, before the stretch: its quote at least
        var left = splitFrom && kept > 1 ? fromText.substring(0, kept) + "\" + " : "";
        var after = end.column - to.getBegin().orElseThrow().column + 1; // where the last literal goes on after the stretch
        var right = splitTo && after < toText.length() - 1 ? " + \"" + toText.substring(after) : "";
        if (first > 0 && left.isEmpty() && !string(prefix(chain, operands.size() - first))) {
            throw new PrimitiveRejected("extract.fragment", "what comes before '" + unit.textBetween(begin, end) + "' is not a String yet");
        }
        var group = (splitFrom ? "\"" : "") + unit.textBetween(begin, end) + (splitTo ? "\"" : "");
        c.sources().apply(Shared.edits(e -> e.replace(unit, from.getBegin().orElseThrow(), to.getEnd().orElseThrow(), left + "(" + group + ")" + right)));
        return from.getBegin().orElseThrow().right(left.length());
    }

    /** The expression a regrouping put in parentheses at {@code at}. */
    static EnclosedExpr enclosedAt(MethodDeclaration method, Position at) {
        return method.findFirst(EnclosedExpr.class, e -> e.getBegin().filter(at::equals).isPresent()).orElseThrow();
    }

    private static List<Expression> operands(BinaryExpr chain) {
        var operands = new ArrayList<Expression>();
        Expression left = chain;
        while (left instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            operands.addFirst(b.getRight());
            left = b.getLeft();
        }
        operands.addFirst(left);
        return operands;
    }

    /** The chain's left part that holds all but its last {@code dropped} operands. */
    private static Expression prefix(BinaryExpr chain, int dropped) {
        Expression left = chain;
        for (var i = 0; i < dropped; i++) left = ((BinaryExpr) left).getLeft();
        return left;
    }

    private static int at(List<Expression> operands, Position position) {
        for (var i = 0; i < operands.size(); i++) {
            if (operands.get(i).getRange().filter(r -> r.contains(position)).isPresent()) return i;
        }
        return -1;
    }

    /** Whether {@code position} is a character of a one-line literal's text where it can be split: between its quotes, in one with no escapes. */
    private static boolean inside(Expression operand, Position position, boolean begins) {
        if (!(operand instanceof StringLiteralExpr literal)) return false;
        var range = literal.getRange().orElseThrow();
        if (range.begin.line != range.end.line || position.line != range.begin.line) return false;
        var offset = position.column - range.begin.column; // in the literal's text, its opening quote at 0
        var text = '"' + literal.getValue() + '"';
        if (text.length() != range.end.column - range.begin.column + 1) return false; // escapes: their columns are not the value's
        return begins ? offset >= 1 && offset <= text.length() - 2 : offset >= 1 && offset <= text.length() - 2;
    }

    private static boolean string(Expression e) {
        try {
            var type = e.calculateResolvedType();
            return type.isReferenceType() && type.asReferenceType().getQualifiedName().equals("java.lang.String");
        } catch (RuntimeException unknown) {
            return false;
        }
    }
}
