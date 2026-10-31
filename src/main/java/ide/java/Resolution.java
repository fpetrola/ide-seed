package ide.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** How uses resolve and how declarations are described in the graph: addresses, kinds, attributes. */
final class Resolution {

    private Resolution() {
    }

    /** Address of the workspace constructor a {@code new} resolves to, or null. */
    static String resolveConstructor(ObjectCreationExpr creation) {
        try {
            var constructor = creation.resolve();
            var params = new ArrayList<String>();
            for (var i = 0; i < constructor.getNumberOfParams(); i++) params.add(simple(constructor.getParam(i).getType().describe()));
            return constructor.declaringType().getQualifiedName() + "#new(" + String.join(",", params) + ")";
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** Qualified name of the type a type use resolves to, or null. */
    static String resolveType(ClassOrInterfaceType type) {
        try {
            return type.resolve().asReferenceType().getQualifiedName();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    static Mode modeOf(Node use) {
        if (use instanceof MethodCallExpr || use instanceof MethodReferenceExpr) return Mode.CALL;
        if (use instanceof ObjectCreationExpr) return Mode.NEW;
        if (use instanceof ClassOrInterfaceType type) return linkMode(type);
        var parent = use.getParentNode().orElseThrow();
        if (parent instanceof AssignExpr assign && assign.getTarget() == use) {
            return assign.getOperator() == AssignExpr.Operator.ASSIGN ? Mode.WRITE : Mode.UPDATE;
        }
        if (parent instanceof UnaryExpr unary && (unary.getOperator().isPrefix() || unary.getOperator().isPostfix())
                && unary.getOperator() != UnaryExpr.Operator.MINUS && unary.getOperator() != UnaryExpr.Operator.PLUS
                && unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && unary.getOperator() != UnaryExpr.Operator.BITWISE_COMPLEMENT) {
            return Mode.UPDATE;
        }
        return Mode.READ;
    }

    /**
     * A type mention is a link when it is an extends or implements clause, or the whole declared type of a field,
     * parameter or method; any other mention (a type argument, a local's type, a cast) is a plain type use.
     */
    static Mode linkMode(ClassOrInterfaceType type) {
        var parent = type.getParentNode().orElse(null);
        return switch (parent) {
            case ClassOrInterfaceDeclaration c -> c.getExtendedTypes().stream().anyMatch(t -> t == type) ? Mode.EXTENDS
                    : c.getImplementedTypes().stream().anyMatch(t -> t == type) ? Mode.IMPLEMENTS : Mode.TYPE;
            case EnumDeclaration e -> e.getImplementedTypes().stream().anyMatch(t -> t == type) ? Mode.IMPLEMENTS : Mode.TYPE;
            case Parameter p -> p.getType() == type ? Mode.DECLARED : Mode.TYPE;
            case MethodDeclaration m -> m.getType() == type ? Mode.DECLARED : Mode.TYPE;
            case VariableDeclarator v -> v.getType() == type
                    && v.getParentNode().filter(FieldDeclaration.class::isInstance).isPresent() ? Mode.DECLARED : Mode.TYPE;
            case null, default -> Mode.TYPE;
        };
    }

    /** Address of a method reference's method ({@code Book::code}), or null. */
    static String resolveMethodReference(MethodReferenceExpr reference) {
        try {
            var method = reference.resolve();
            var params = new ArrayList<String>();
            for (var i = 0; i < method.getNumberOfParams(); i++) params.add(simple(method.getParam(i).getType().describe()));
            return method.declaringType().getQualifiedName() + "#" + method.getName() + "(" + String.join(",", params) + ")";
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    static String resolveMethodStrictly(MethodCallExpr call) {
        try {
            var method = call.resolve();
            var params = new ArrayList<String>();
            for (var i = 0; i < method.getNumberOfParams(); i++) params.add(simple(method.getParam(i).getType().describe()));
            return method.declaringType().getQualifiedName() + "#" + method.getName() + "(" + String.join(",", params) + ")";
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    static List<Expression> fieldUses(CompilationUnit cu) {
        var uses = new ArrayList<Expression>();
        uses.addAll(cu.findAll(NameExpr.class));
        uses.addAll(cu.findAll(FieldAccessExpr.class));
        return uses;
    }

    /** Address of the field the expression resolves to, or null if it is not a (resolvable) field use. */
    static String resolveField(Expression expr) {
        try {
            ResolvedValueDeclaration decl = expr.isNameExpr() ? expr.asNameExpr().resolve() : expr.asFieldAccessExpr().resolve();
            if (!decl.isField()) return null;
            return ((ResolvedFieldDeclaration) decl).declaringType().getQualifiedName() + "#" + decl.getName();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /**
     * {@code a.B#m(int,String)}: parameter types resolved (so declarations and calls agree) and written with simple
     * names (so moving a type does not change the address of the methods that take it).
     */
    static String methodAddress(String typeName, CallableDeclaration<?> method) {
        var name = method instanceof ConstructorDeclaration ? "new" : method.getNameAsString();
        return typeName + "#" + name + method.getParameters().stream()
                .map(p -> simple(describe(p.getType()))).collect(Collectors.joining(",", "(", ")"));
    }

    static final java.util.regex.Pattern PACKAGE_PREFIX = java.util.regex.Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+");

    static String simple(String described) {
        return PACKAGE_PREFIX.matcher(described).replaceAll("").replace(" ", "");
    }

    static String describe(Type type) {
        try {
            return type.resolve().describe();
        } catch (RuntimeException unresolved) {
            return type.asString();
        }
    }

    /** Classes, interfaces and enums of a unit, outermost first. */
    static List<TypeDeclaration<?>> types(CompilationUnit cu) {
        var types = new ArrayList<TypeDeclaration<?>>();
        cu.findAll(TypeDeclaration.class).forEach(t -> {
            if (t instanceof ClassOrInterfaceDeclaration || t instanceof EnumDeclaration) types.add(t);
        });
        return types;
    }

    static Kind kindOf(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) return Kind.ENUM;
        return ((ClassOrInterfaceDeclaration) type).isInterface() ? Kind.INTERFACE : Kind.CLASS;
    }

    /** The facts a declaration's modifiers state. */
    static Map<Fact, String> attributes(NodeWithModifiers<?> node) {
        var visibility = node.getAccessSpecifier().asString();
        return Map.of(Fact.VISIBILITY, visibility.isEmpty() ? "package" : visibility,
                Fact.STATIC, String.valueOf(node.hasModifier(Modifier.Keyword.STATIC)),
                Fact.ABSTRACT, String.valueOf(node.hasModifier(Modifier.Keyword.ABSTRACT)),
                Fact.FINAL, String.valueOf(node.hasModifier(Modifier.Keyword.FINAL)));
    }
}
