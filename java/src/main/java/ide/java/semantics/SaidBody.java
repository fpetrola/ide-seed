package ide.java.semantics;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import ide.program.Body;
import ide.program.ElementId;
import ide.program.Kind;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A Java statement said in no language ({@link Body}), when all of it can be: a field given a value, a value answered,
 * the object made first by another constructor; values that are fields, parameters, calls and new objects of what the
 * program has. Anything else (a local, a literal, a loop) cannot be said, and then nothing is.
 */
public final class SaidBody {

    private final Map<Node, ElementId> targets;
    private final java.util.Set<ElementId> fields;
    private final Map<String, ElementId> parameters;

    private SaidBody(Map<Node, ElementId> targets, java.util.Set<ElementId> fields, Map<String, ElementId> parameters) {
        this.targets = targets;
        this.fields = fields;
        this.parameters = parameters;
    }

    /** What a statement does, said in no language when all of it can be; nothing for anything else. */
    public static Optional<String> of(Reader.Reading reading, ElementId element) {
        var program = reading.program();
        if (!program.contains(element) || program.element(element).kind() != Kind.STATEMENT) return Optional.empty();
        var targets = new IdentityHashMap<Node, ElementId>();
        var fields = new java.util.HashSet<ElementId>();
        for (var reference : program.referencesIn(element)) {
            targets.put(reading.use(reference.id()), reference.target());
            if (program.contains(reference.target()) && program.element(reference.target()).kind() == Kind.FIELD) fields.add(reference.target());
        }
        var parameters = new java.util.HashMap<String, ElementId>();
        program.children(program.memberOf(element).id()).stream().filter(e -> e.kind() == Kind.PARAM).forEach(p -> parameters.put(p.name(), p.id()));
        return Optional.ofNullable(new SaidBody(targets, fields, parameters).statement((Statement) reading.declaration(element).node()));
    }

    private String statement(Statement statement) {
        return switch (statement) {
            case ExpressionStmt e when e.getExpression() instanceof AssignExpr assign && assign.getOperator() == AssignExpr.Operator.ASSIGN -> {
                var field = field(assign.getTarget());
                var value = value(assign.getValue());
                yield field == null || value == null ? null : Body.name(field) + " = " + value;
            }
            case ReturnStmt r when r.getExpression().isPresent() -> {
                var value = value(r.getExpression().get());
                yield value == null ? null : "return " + value;
            }
            case ExplicitConstructorInvocationStmt made when targets.containsKey(made) -> {
                var arguments = arguments(made.getArguments());
                yield arguments == null ? null : Body.name(targets.get(made)) + "(" + arguments + ")";
            }
            default -> null;
        };
    }

    /** A field the program has, named alone or through {@code this}. */
    private ElementId field(Expression expression) {
        var target = targets.get(expression);
        if (target == null || !fields.contains(target)) return null;
        if (expression instanceof FieldAccessExpr access && !(access.getScope() instanceof ThisExpr)) return null;
        return target;
    }

    private String value(Expression expression) {
        if (expression instanceof NameExpr name && (!targets.containsKey(name) || parameters.containsValue(targets.get(name)))) {
            var parameter = targets.containsKey(name) ? targets.get(name) : parameters.get(name.getNameAsString()); // a read of it
            return parameter == null ? null : Body.name(parameter);
        }
        var field = field(expression);
        if (field != null) return Body.name(field);
        var target = targets.get(expression);
        if (target == null) return null;
        return switch (expression) {
            case ObjectCreationExpr creation when creation.getAnonymousClassBody().isEmpty() -> {
                var arguments = arguments(creation.getArguments());
                yield arguments == null ? null : "new " + Body.name(target) + "(" + arguments + ")";
            }
            case MethodCallExpr call -> {
                var arguments = arguments(call.getArguments());
                if (arguments == null) yield null;
                var receiver = call.getScope().filter(scope -> !(scope instanceof ThisExpr)).map(this::value);
                if (call.getScope().isPresent() && !(call.getScope().get() instanceof ThisExpr) && receiver.isEmpty()) yield null;
                yield receiver.map(r -> r + ".").orElse("") + Body.name(target) + "(" + arguments + ")";
            }
            default -> null;
        };
    }

    private String arguments(List<Expression> arguments) {
        var said = new ArrayList<String>();
        for (var argument : arguments) {
            var value = value(argument);
            if (value == null) return null;
            said.add(value);
        }
        return String.join(", ", said);
    }
}
