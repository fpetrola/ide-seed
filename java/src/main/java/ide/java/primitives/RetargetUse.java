package ide.java.primitives;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ide.java.syntax.Imports;
import ide.java.text.EditSet;
import ide.program.Change;
import ide.program.Element;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Unresolved;
import ide.projection.PrimitiveRejected;

import java.util.List;

/**
 * Points a use at another element. A use of the same kind just names the other one; a type use names the other type
 * (imported if needed); a call that passes nothing becomes a field read; a field read becomes {@code getter()}, a
 * write {@code x = v} used as a statement
 * {@code setter(v)}, a {@code new} a factory call. Updates (compound assignment, ++/--) and assignments whose value
 * is used are refused: they would change how many times things are evaluated. A type written in a parameter is part
 * of its method's signature: the method keeps its identity at its new address.
 */
final class RetargetUse {

    private RetargetUse() {
    }

    static Change apply(Primitive.Retarget retarget, Context c, Program before) {
        var unresolved = before.unresolved(retarget.reference());
        if (unresolved.isPresent()) return bind(unresolved.get(), retarget, c, before);
        var ref = before.reference(retarget.reference());
        var target = before.element(retarget.target());
        var signature = Signature.of(c, before.element(ref.site())); // read before the text changes
        var node = c.use(ref.id());
        var unit = c.sources().unitOf(node);
        var edits = new EditSet();
        if (ref.mode().isTypeUse()) {
            edits.replace(unit, node instanceof ClassOrInterfaceType t ? t.getName() : node, target.name());
            if (!Imports.names(unit, target.qualified())) Imports.add(edits, unit, target.qualified());
        } else if (target.kind() == before.element(ref.target()).kind()) {
            Shared.renameUse(c, edits, node, target.name());
        } else if (ref.mode() == Mode.CALL && target.kind() == Kind.FIELD) { // a call of nothing but a value: the value
            var call = (com.github.javaparser.ast.expr.MethodCallExpr) node;
            if (!call.getArguments().isEmpty()) throw new PrimitiveRejected("retarget.call", call + " passes arguments a field cannot take");
            edits.replace(unit, call, call.getScope().map(scope -> unit.textOf(scope) + ".").orElse("") + target.name());
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
        signature.follow(c);
        return retarget.intent(before, null);
    }

    /**
     * A use that bound nothing names {@code target}: a written type by its simple name, imported if it is not nameable
     * yet; a field read or written, or a method called, by the target's name, if it is a field or a method as the use
     * needs.
     */
    private static Change bind(Unresolved use, Primitive.Retarget retarget, Context c, Program before) {
        var target = before.element(retarget.target());
        var used = c.use(use.id());
        var fits = used instanceof ClassOrInterfaceType ? target.kind().isType()
                : use.mode() == Mode.CALL ? target.kind() == Kind.METHOD
                : !use.mode().isTypeUse() && target.kind() == Kind.FIELD;
        if (!fits) throw new PrimitiveRejected("retarget." + use.mode().label(), "cannot bind " + use.name() + " to " + target.qualified());
        if (!(used instanceof ClassOrInterfaceType node)) {
            var edits = new EditSet();
            Shared.renameUse(c, edits, used, target.name());
            c.sources().apply(edits);
            return retarget.intent(before, null);
        }
        var signature = Signature.of(c, before.element(use.site()));
        var unit = c.sources().unitOf(node);
        var edits = new EditSet();
        var name = Imports.nameable(edits, unit, target.qualified());
        if (!node.getNameAsString().equals(name)) edits.replace(unit, node.getName(), name);
        c.sources().apply(edits);
        signature.follow(c);
        return retarget.intent(before, null);
    }

    /** The method whose signature a use in one of its parameters is part of (none for other uses), read before the text changes. */
    private record Signature(Element method, String path, List<String> parameters) {

        static Signature of(Context c, Element site) {
            if (site.kind() != Kind.PARAM) return new Signature(null, null, null);
            var method = c.program().element(site.parent());
            var declaration = c.declaration(method);
            return new Signature(method, declaration.unit().path(), Shared.parameterNames(declaration.as(CallableDeclaration.class)));
        }

        /** The method keeps its identity at its new address. */
        void follow(Context c) {
            if (method != null) Shared.resign(c, method, path, parameters);
        }
    }
}
