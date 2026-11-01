package ide.java.semantics;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;
import ide.projection.Needs;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What a use that binds nothing is known to need, in Java and as its file writes types: the type its place expects
 * of its value, and what a call passes. What cannot be told is {@code Object}.
 */
public final class UseNeeds {

    private static final String UNKNOWN = "Object";

    private UseNeeds() {
    }

    /** The type the place of {@code use} expects of its value; {@code void} for a call whose value nothing takes. */
    static String expected(Expression use) {
        var unit = use.findCompilationUnit().orElseThrow();
        var parent = use.getParentNode().orElse(null);
        return switch (parent) {
            case ExpressionStmt statement -> "void";
            case EnclosedExpr enclosed -> expected(enclosed);
            case VariableDeclarator variable when variable.getType().isVarType() -> UNKNOWN;
            case VariableDeclarator variable -> declared(variable.getType(), unit);
            case ReturnStmt returned -> {
                var callable = returned.findAncestor(Node.class, n -> n instanceof MethodDeclaration || n instanceof LambdaExpr);
                yield callable.orElse(null) instanceof MethodDeclaration method ? declared(method.getType(), unit) : UNKNOWN;
            }
            case AssignExpr assign -> typeOf(assign.getTarget() == use ? assign.getValue() : assign.getTarget(), unit);
            case BinaryExpr binary when binary.getOperator() == BinaryExpr.Operator.AND
                    || binary.getOperator() == BinaryExpr.Operator.OR -> "boolean";
            case BinaryExpr binary -> typeOf(binary.getLeft() == use ? binary.getRight() : binary.getLeft(), unit);
            case UnaryExpr unary -> unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT ? "boolean" : "int";
            case IfStmt test when test.getCondition() == use -> "boolean";
            case WhileStmt test when test.getCondition() == use -> "boolean";
            case DoStmt test when test.getCondition() == use -> "boolean";
            case ConditionalExpr test when test.getCondition() == use -> "boolean";
            case null, default -> UNKNOWN;
        };
    }

    /** What a use needs: what its place expects, and what it passes if it is a call. */
    public static Needs of(Expression use) {
        return new Needs(expected(use), use instanceof com.github.javaparser.ast.nodeTypes.NodeWithArguments<?> call ? arguments(call.getArguments()) : List.of());
    }

    /** What a call passes: each argument named as a parameter that takes it would be, once, with its type. */
    static List<Needs.Argument> arguments(List<Expression> passed) {
        var arguments = new ArrayList<Needs.Argument>();
        var taken = new HashSet<String>();
        for (var argument : passed) {
            var unit = argument.findCompilationUnit().orElseThrow();
            var type = typeOf(argument, unit);
            var name = nameFor(argument, type);
            if (SourceVersion.isKeyword(name)) name += "1";
            var unique = name;
            for (var n = 2; !taken.add(unique); n++) unique = name + n;
            arguments.add(new Needs.Argument(unique, type));
        }
        return arguments;
    }

    private static String nameFor(Expression argument, String type) {
        return switch (argument) {
            case NameExpr name -> name.getNameAsString();
            case FieldAccessExpr access -> access.getNameAsString();
            case MethodCallExpr call when accessor(call.getNameAsString()) != null -> accessor(call.getNameAsString());
            case MethodCallExpr call -> call.getNameAsString();
            default -> {
                var simple = type.replaceAll("<.*", "").replace("[]", "");
                simple = simple.substring(simple.lastIndexOf('.') + 1);
                yield Character.isLowerCase(simple.charAt(0)) ? simple.substring(0, 1)
                        : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
            }
        };
    }

    /** {@code x} for {@code getX} or {@code isX}; else null. */
    private static String accessor(String method) {
        for (var prefix : List.of("get", "is")) {
            if (method.length() > prefix.length() && method.startsWith(prefix) && Character.isUpperCase(method.charAt(prefix.length()))) {
                return Character.toLowerCase(method.charAt(prefix.length())) + method.substring(prefix.length() + 1);
            }
        }
        return null;
    }

    private static String typeOf(Expression expression, CompilationUnit unit) {
        try {
            return written(expression.calculateResolvedType(), unit);
        } catch (RuntimeException unknown) {
            return UNKNOWN;
        }
    }

    private static String declared(Type type, CompilationUnit unit) {
        try {
            return written(type.resolve(), unit);
        } catch (RuntimeException unknown) {
            return type.asString();
        }
    }

    /** A type as the file writes it: by its simple name where the file can name it so, else qualified. */
    public static String written(ResolvedType type, CompilationUnit unit) {
        if (type.isPrimitive()) return type.asPrimitive().describe();
        if (type.isVoid()) return "void";
        if (type.isArray()) return written(type.asArrayType().getComponentType(), unit) + "[]";
        if (type.isTypeVariable()) return type.asTypeParameter().getName();
        if (type.isWildcard()) return "?";
        if (!type.isReferenceType()) return UNKNOWN;
        var reference = type.asReferenceType();
        var qualified = reference.getQualifiedName();
        var name = nameable(qualified, unit) ? qualified.substring(qualified.lastIndexOf('.') + 1) : qualified;
        var values = reference.typeParametersValues();
        return values.isEmpty() ? name : name + "<" + values.stream().map(v -> written(v, unit)).collect(Collectors.joining(", ")) + ">";
    }

    private static boolean nameable(String qualified, CompilationUnit unit) {
        var pkg = qualified.substring(0, Math.max(0, qualified.lastIndexOf('.')));
        return pkg.equals("java.lang")
                || unit.getPackageDeclaration().map(p -> p.getNameAsString().equals(pkg)).orElse(pkg.isEmpty())
                || unit.getImports().stream().anyMatch(i -> !i.isStatic() && !i.isAsterisk() && i.getNameAsString().equals(qualified));
    }
}
