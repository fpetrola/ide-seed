package ide.java.primitives;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ide.java.syntax.Imports;
import ide.java.text.EditSet;
import ide.program.Change;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

/**
 * Points a use at another element. A use of the same kind just names the other one; a type use names the other type
 * (imported if needed); a field read becomes {@code getter()}, a write {@code x = v} used as a statement
 * {@code setter(v)}, a {@code new} a factory call. Updates (compound assignment, ++/--) and assignments whose value
 * is used are refused: they would change how many times things are evaluated.
 */
final class RetargetUse {

    private RetargetUse() {
    }

    static Change apply(Primitive.Retarget retarget, Context c, Program before) {
        var ref = before.reference(retarget.reference());
        var target = before.element(retarget.target());
        var node = c.use(ref.id());
        var unit = c.sources().unitOf(node);
        var edits = new EditSet();
        if (ref.mode().isTypeUse()) {
            edits.replace(unit, node instanceof ClassOrInterfaceType t ? t.getName() : node, target.name());
            if (!Imports.names(unit, target.qualified())) Imports.add(edits, unit, target.qualified());
        } else if (target.kind() == before.element(ref.target()).kind()) {
            Shared.renameUse(c, edits, node, target.name());
        } else {
            if (target.kind() != Kind.METHOD) throw new IllegalArgumentException("can only retarget to methods: " + target);
            var expression = (Expression) node;
            var receiver = expression.isFieldAccessExpr() ? unit.textOf(expression.asFieldAccessExpr().getScope()) + "." : "";
            switch (ref.mode()) {
                case READ -> edits.replace(unit, expression, receiver + target.name() + "()");
                case NEW -> {
                    var creation = (ObjectCreationExpr) expression;
                    if (creation.getAnonymousClassBody().isPresent()) {
                        throw new PrimitiveRejected("retarget.anonymous-class", "cannot turn " + creation + " into a call");
                    }
                    var arguments = creation.getArguments().isEmpty() ? "" : unit.textBetween(
                            creation.getArguments().getFirst().orElseThrow().getBegin().orElseThrow(),
                            creation.getArguments().getLast().orElseThrow().getEnd().orElseThrow());
                    edits.replace(unit, creation, creation.getType().getNameWithScope() + "." + target.name() + "(" + arguments + ")");
                }
                case WRITE -> {
                    var assign = (AssignExpr) expression.getParentNode().orElseThrow();
                    if (!(assign.getParentNode().orElseThrow() instanceof ExpressionStmt)) {
                        throw new PrimitiveRejected("retarget.value-used", "the value of " + assign + " is used");
                    }
                    edits.replace(unit, assign, receiver + target.name() + "(" + unit.textOf(assign.getValue()) + ")");
                }
                default -> throw new PrimitiveRejected("retarget." + ref.mode().label(),
                        "cannot express " + expression.getParentNode().orElseThrow() + " with " + target.qualified());
            }
        }
        c.sources().apply(edits);
        return retarget.intent(before, null);
    }
}
