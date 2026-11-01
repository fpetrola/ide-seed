package ide.java.semantics;

import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.resolution.UnsolvedSymbolException;
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

    /** The constructor a {@code super(...)} or {@code this(...)} resolves to, or null. */
    public static ResolvedConstructorDeclaration resolveConstructor(ExplicitConstructorInvocationStmt invocation) {
        try {
            return invocation.resolve();
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

    /**
     * Whether a written type binds nothing: no type of the workspace or of a library, no type variable. The package
     * written before a qualified type name ({@code java.util} in {@code java.util.List}) is no type use at all, and
     * what is before a {@code ::} may as well be a value ({@code names} in {@code names::size}).
     */
    public static boolean bindsNothing(ClassOrInterfaceType type) {
        var scopeOfAnother = type.getParentNode().filter(parent -> parent instanceof ClassOrInterfaceType outer
                && outer.getScope().filter(scope -> scope == type).isPresent()).isPresent();
        if (scopeOfAnother || type.getParentNode().filter(TypeExpr.class::isInstance).isPresent()) return false;
        try {
            type.resolve();
            return false;
        } catch (UnsolvedSymbolException unresolved) {
            return true;
        } catch (RuntimeException unknown) { // what the solver cannot model is not known to bind nothing
            return false;
        }
    }

    /** The kind a type of the JDK has in the program. */
    public static Kind kindOf(Class<?> type) {
        return type.isInterface() ? Kind.INTERFACE : type.isEnum() ? Kind.ENUM : Kind.CLASS;
    }

    /** The kind a resolved type has in the program. */
    public static Kind kindOf(ResolvedTypeDeclaration type) {
        return type.isInterface() || type.isAnnotation() ? Kind.INTERFACE : type.isEnum() ? Kind.ENUM : Kind.CLASS;
    }

    public static Mode modeOf(Node use) {
        if (use instanceof MethodCallExpr || use instanceof MethodReferenceExpr || use instanceof ExplicitConstructorInvocationStmt) return Mode.CALL;
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
            case RecordDeclaration r -> r.getImplementedTypes().stream().anyMatch(t -> t == type) ? Mode.IMPLEMENTS : Mode.TYPE;
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

    /**
     * Whether a name used as a value surely binds nothing: the solver says no field, local or parameter has it. A name
     * before a dot may be a package or a type, a case label an enum's constant, a name in an annotation
     * anything: none of them is said to bind nothing, nor what the solver cannot model.
     */
    public static boolean valueBindsNothing(Expression use) {
        var parent = use.getParentNode().orElse(null);
        if (parent instanceof FieldAccessExpr access && access.getScope() == use) return false;
        if (parent instanceof MethodCallExpr call && call.getScope().filter(scope -> scope == use).isPresent()) return false;
        if (parent instanceof SwitchEntry || use.findAncestor(AnnotationExpr.class).isPresent()) return false;
        try {
            if (use instanceof FieldAccessExpr access && !access.getScope().calculateResolvedType().isReferenceType()) return false;
        } catch (RuntimeException unknown) {
            return false; // a receiver of no known type: its members are not known either
        }
        try {
            if (use instanceof NameExpr name) name.resolve();
            else use.asFieldAccessExpr().resolve();
            return false;
        } catch (UnsolvedSymbolException unresolved) {
            return true;
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /**
     * Whether a call surely binds nothing: no method has its name where it is looked up, in its receiver's type or in
     * the types it is written in, with their supertypes, nor does a static import bring one. A call with a name that
     * exists binds something, or is written with the wrong arguments, which is another error.
     */
    public static boolean callBindsNothing(MethodCallExpr call) {
        var name = call.getNameAsString();
        try {
            if (call.getScope().isPresent()) {
                var type = call.getScope().get().calculateResolvedType();
                return type.isReferenceType() && type.asReferenceType().getTypeDeclaration().filter(t -> !declares(t, name)).isPresent();
            }
            var unit = call.findCompilationUnit().orElseThrow();
            if (unit.getImports().stream().anyMatch(i -> i.isStatic() && (i.isAsterisk() || i.getName().getIdentifier().equals(name)))) {
                return false;
            }
            for (Node at = call; at != null; at = at.getParentNode().orElse(null)) {
                if (at instanceof TypeDeclaration<?> type && declares(type.resolve(), name)) return false;
                if (at instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent()
                        && (declares(creation.getType().resolve().asReferenceType().getTypeDeclaration().orElseThrow(), name)
                        || creation.getAnonymousClassBody().get().stream().anyMatch(m -> m instanceof MethodDeclaration d && d.getNameAsString().equals(name)))) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** What every object answers, which the solver does not list for an interface. */
    private static final java.util.Set<String> OBJECT = java.util.Set.of("equals", "hashCode", "toString", "getClass",
            "notify", "notifyAll", "wait", "clone", "finalize");

    /** Whether a type has a method by that name: its own, inherited, every object's, or an enum's implicit ones. */
    private static boolean declares(ResolvedReferenceTypeDeclaration type, String method) {
        return OBJECT.contains(method) || type.isEnum() && (method.equals("values") || method.equals("valueOf"))
                || type.getAllMethods().stream().anyMatch(m -> m.getName().equals(method));
    }

    /**
     * Whether a {@code new} surely binds nothing: its type is the workspace's, plain (no anonymous body), and none of
     * its constructors takes as many arguments as it passes; with none declared, the one Java gives takes none.
     */
    public static boolean constructorBindsNothing(ObjectCreationExpr creation, java.util.function.Predicate<String> workspace) {
        if (creation.getAnonymousClassBody().isPresent()) return false;
        try {
            var type = creation.getType().resolve().asReferenceType().getTypeDeclaration().orElse(null);
            if (type == null || !workspace.test(type.getQualifiedName())) return false;
            var passed = creation.getArguments().size();
            var constructors = type.getConstructors();
            return constructors.isEmpty() ? passed > 0 : constructors.stream().noneMatch(c -> c.getNumberOfParams() == passed
                    || c.hasVariadicParameter() && passed >= c.getNumberOfParams() - 1);
        } catch (RuntimeException unknown) {
            return false;
        }
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

    /**
     * The types of a unit, outermost first: classes and records, interfaces and annotation types, enums. A type declared
     * inside a method belongs to the method, as its locals do: it has no qualified name, and is no type of the program.
     */
    public static List<TypeDeclaration<?>> types(CompilationUnit cu) {
        var types = new ArrayList<TypeDeclaration<?>>();
        cu.findAll(TypeDeclaration.class).forEach(type -> {
            if (type.getFullyQualifiedName().isPresent()) types.add(type);
        });
        return types;
    }

    /** A record is a class and an annotation type an interface, as Java says they are. */
    public static Kind kindOf(TypeDeclaration<?> type) {
        return switch (type) {
            case EnumDeclaration e -> Kind.ENUM;
            case AnnotationDeclaration a -> Kind.INTERFACE;
            case ClassOrInterfaceDeclaration c when c.isInterface() -> Kind.INTERFACE;
            default -> Kind.CLASS;
        };
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
