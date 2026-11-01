package ide.java.semantics;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** How uses resolve and how declarations are described in the graph: kinds, modes, facts. */
public final class Resolution {

    private Resolution() {
    }

    /** The constructor a {@code new} resolves to, or null. */
    public static ResolvedConstructorDeclaration resolveConstructor(ObjectCreationExpr creation) {
        try {
            return creation.resolve();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** Qualified name of the type a type use resolves to, or null. */
    public static String resolveType(ClassOrInterfaceType type) {
        try {
            return type.resolve().asReferenceType().getQualifiedName();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** The declaration a type use resolves to, in the workspace or in a library, or null. */
    public static ResolvedReferenceTypeDeclaration resolveDeclaration(ClassOrInterfaceType type) {
        try {
            return type.resolve().asReferenceType().getTypeDeclaration().orElse(null);
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** The kind a resolved type has in the program. */
    public static Kind kindOf(ResolvedTypeDeclaration type) {
        return type.isInterface() || type.isAnnotation() ? Kind.INTERFACE : type.isEnum() ? Kind.ENUM : Kind.CLASS;
    }

    public static Mode modeOf(Node use) {
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
    public static Mode linkMode(ClassOrInterfaceType type) {
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

    /** The method a method reference ({@code Book::code}) resolves to, or null. */
    public static ResolvedMethodDeclaration resolveMethodReference(MethodReferenceExpr reference) {
        try {
            return reference.resolve();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** The method a call resolves to, or null. */
    public static ResolvedMethodDeclaration resolveMethodStrictly(MethodCallExpr call) {
        try {
            return call.resolve();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    public static List<Expression> fieldUses(CompilationUnit cu) {
        var uses = new ArrayList<Expression>();
        uses.addAll(cu.findAll(NameExpr.class));
        uses.addAll(cu.findAll(FieldAccessExpr.class));
        return uses;
    }

    /** The field the expression resolves to, or null if it is not a (resolvable) field use. */
    public static ResolvedFieldDeclaration resolveField(Expression expr) {
        try {
            ResolvedValueDeclaration decl = expr.isNameExpr() ? expr.asNameExpr().resolve() : expr.asFieldAccessExpr().resolve();
            return decl.isField() ? (ResolvedFieldDeclaration) decl : null;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** The resolved description of a declared type, or the type as written when it does not resolve. */
    public static String describe(Type type) {
        try {
            return type.resolve().describe();
        } catch (RuntimeException unresolved) {
            return type.asString();
        }
    }

    /** Classes, interfaces and enums of a unit, outermost first. */
    public static List<TypeDeclaration<?>> types(CompilationUnit cu) {
        var types = new ArrayList<TypeDeclaration<?>>();
        cu.findAll(TypeDeclaration.class).forEach(t -> {
            if (t instanceof ClassOrInterfaceDeclaration || t instanceof EnumDeclaration) types.add(t);
        });
        return types;
    }

    public static Kind kindOf(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) return Kind.ENUM;
        return ((ClassOrInterfaceDeclaration) type).isInterface() ? Kind.INTERFACE : Kind.CLASS;
    }

    /** The facts a declaration's modifiers state. */
    public static Map<Fact, String> attributes(NodeWithModifiers<?> node) {
        var visibility = node.getAccessSpecifier().asString();
        return Map.of(Fact.VISIBILITY, visibility.isEmpty() ? "package" : visibility,
                Fact.STATIC, String.valueOf(node.hasModifier(Modifier.Keyword.STATIC)),
                Fact.ABSTRACT, String.valueOf(node.hasModifier(Modifier.Keyword.ABSTRACT)),
                Fact.FINAL, String.valueOf(node.hasModifier(Modifier.Keyword.FINAL)));
    }
}
