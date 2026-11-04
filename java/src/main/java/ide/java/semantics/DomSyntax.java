package ide.java.semantics;

import ide.program.Fact;
import ide.program.Kind;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.IntersectionType;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WildcardType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java as Eclipse's DOM writes it, read the way the program has always been read: which types a file declares, what a
 * declaration's modifiers say, a type as written, which statements and locals a body has. No name is bound here.
 */
public final class DomSyntax {

    private DomSyntax() {
    }

    /**
     * The types of a unit with a qualified name, outermost first, in the order they are written: not one declared in a
     * method (it belongs to the method), nor anything inside it.
     */
    public static List<AbstractTypeDeclaration> types(CompilationUnit unit) {
        var types = new ArrayList<AbstractTypeDeclaration>();
        for (var type : unit.types()) if (type instanceof AbstractTypeDeclaration declared) collect(declared, types);
        return types;
    }

    private static void collect(AbstractTypeDeclaration type, List<AbstractTypeDeclaration> into) {
        into.add(type);
        for (var member : type.bodyDeclarations()) if (member instanceof AbstractTypeDeclaration nested) collect(nested, into);
    }

    /** {@code a.Outer.Inner}: the package, and every type it is declared in. */
    public static String qualified(AbstractTypeDeclaration type) {
        var name = new StringBuilder(type.getName().getIdentifier());
        for (ASTNode at = type.getParent(); at != null; at = at.getParent()) {
            if (at instanceof AbstractTypeDeclaration outer) name.insert(0, outer.getName().getIdentifier() + ".");
            if (at instanceof CompilationUnit unit && unit.getPackage() != null) {
                name.insert(0, unit.getPackage().getName().getFullyQualifiedName() + ".");
            }
        }
        return name.toString();
    }

    /** A record is a class and an annotation type an interface, as Java says they are. */
    public static Kind kindOf(AbstractTypeDeclaration type) {
        return switch (type) {
            case EnumDeclaration e -> Kind.ENUM;
            case AnnotationTypeDeclaration a -> Kind.INTERFACE;
            case TypeDeclaration t when t.isInterface() -> Kind.INTERFACE;
            default -> Kind.CLASS;
        };
    }

    /** The fields, constructors and methods a type declares, each kind in the order written. */
    public static List<FieldDeclaration> fields(AbstractTypeDeclaration type) {
        var fields = new ArrayList<FieldDeclaration>();
        for (var member : type.bodyDeclarations()) if (member instanceof FieldDeclaration field) fields.add(field);
        return fields;
    }

    public static List<MethodDeclaration> constructors(AbstractTypeDeclaration type) {
        return callables(type).stream().filter(MethodDeclaration::isConstructor).toList();
    }

    public static List<MethodDeclaration> methods(AbstractTypeDeclaration type) {
        return callables(type).stream().filter(m -> !m.isConstructor()).toList();
    }

    private static List<MethodDeclaration> callables(AbstractTypeDeclaration type) {
        var callables = new ArrayList<MethodDeclaration>();
        for (var member : type.bodyDeclarations()) if (member instanceof MethodDeclaration m && !m.isCompactConstructor()) callables.add(m);
        return callables;
    }

    /** The facts a declaration's modifiers and annotations state, as the program has them. */
    public static Map<Fact, String> attributes(BodyDeclaration declaration, String text) {
        var flags = declaration.getModifiers();
        var visibility = Modifier.isPublic(flags) ? "public" : Modifier.isProtected(flags) ? "protected"
                : Modifier.isPrivate(flags) ? "private" : "package";
        var facts = new HashMap<>(Map.of(Fact.VISIBILITY, visibility, Fact.STATIC, String.valueOf(Modifier.isStatic(flags)),
                Fact.ABSTRACT, String.valueOf(Modifier.isAbstract(flags)), Fact.FINAL, String.valueOf(Modifier.isFinal(flags))));
        var annotations = new ArrayList<String>();
        for (var modifier : declaration.modifiers()) {
            if (modifier instanceof IExtendedModifier extended && extended.isAnnotation()) annotations.add(textOf((ASTNode) modifier, text));
        }
        if (!annotations.isEmpty()) facts.put(Fact.ANNOTATIONS, String.join(" ", annotations));
        return Map.copyOf(facts);
    }

    /** A node's text as written, its blanks as one. */
    public static String textOf(ASTNode node, String text) {
        return text.substring(node.getStartPosition(), node.getStartPosition() + node.getLength()).replaceAll("\\s+", " ");
    }

    /** A type as the program writes it: {@code Map<String, List<Item>>}, {@code int[]}, {@code ? extends Item}. */
    public static String written(Type type, int extraDimensions) {
        return written(type) + "[]".repeat(extraDimensions);
    }

    public static String written(Type type) {
        return switch (type) {
            case PrimitiveType primitive -> primitive.getPrimitiveTypeCode().toString();
            case SimpleType simple -> simple.getName().getFullyQualifiedName();
            case QualifiedType qualified -> written(qualified.getQualifier()) + "." + qualified.getName().getIdentifier();
            case NameQualifiedType qualified -> qualified.getQualifier().getFullyQualifiedName() + "." + qualified.getName().getIdentifier();
            case ParameterizedType parameterized -> written(parameterized.getType()) + "<"
                    + String.join(",", parameterized.typeArguments().stream().map(t -> written((Type) t)).toList()) + ">";
            case ArrayType array -> written(array.getElementType()) + "[]".repeat(array.getDimensions());
            case WildcardType wildcard -> wildcard.getBound() == null ? "?"
                    : (wildcard.isUpperBound() ? "? extends " : "? super ") + written(wildcard.getBound());
            case UnionType union -> String.join(" | ", union.types().stream().map(t -> written((Type) t)).toList());
            case IntersectionType intersection -> String.join(" & ", intersection.types().stream().map(t -> written((Type) t)).toList());
            default -> type.toString();
        };
    }

    /** Where a declaration starts as the program has it: after its doc comment, at its first modifier or keyword. */
    public static int start(ASTNode node, String text) {
        var start = node.getStartPosition();
        var doc = node instanceof BodyDeclaration declaration ? declaration.getJavadoc() : null;
        if (doc == null) return start;
        return token(text, doc.getStartPosition() + doc.getLength());
    }

    /** The first character from {@code at} that is neither blank nor in a comment. */
    public static int token(String text, int at) {
        while (at < text.length()) {
            var c = text.charAt(at);
            if (Character.isWhitespace(c)) at++;
            else if (text.startsWith("//", at)) at = Math.max(text.indexOf('\n', at), text.length() - 1) + 1;
            else if (text.startsWith("/*", at)) at = text.indexOf("*/", at + 2) < 0 ? text.length() : text.indexOf("*/", at + 2) + 2;
            else return at;
        }
        return at;
    }

    /** The last character of a node. */
    public static int end(ASTNode node) {
        return node.getStartPosition() + node.getLength() - 1;
    }

    /** The statements of a method's or constructor's body, in order; none if it has no body. */
    public static List<Statement> statements(MethodDeclaration callable) {
        return callable.getBody() == null ? List.of() : callable.getBody().statements();
    }

    /**
     * The statements a statement holds in its blocks, in order (its branches, its loop's body, its catches, its switch's
     * entries): what they hold is theirs, and a lambda's or a class's are not statements of the body.
     */
    public static List<Statement> nested(Statement holder) {
        var found = new ArrayList<Statement>();
        for (var child : children(holder)) collect(child, found);
        found.sort(Comparator.comparingInt(ASTNode::getStartPosition));
        return found;
    }

    private static void collect(ASTNode node, List<Statement> found) {
        if (node instanceof Block || node instanceof CatchClause) {
            for (var child : children(node)) collect(child, found);
        } else if (node instanceof Statement statement && !(node instanceof SwitchCase)) {
            found.add(statement);
        }
    }

    /** A node's children, as the DOM's structure has them. */
    static List<ASTNode> children(ASTNode node) {
        var children = new ArrayList<ASTNode>();
        for (var property : node.structuralPropertiesForType()) {
            var value = node.getStructuralProperty((org.eclipse.jdt.core.dom.StructuralPropertyDescriptor) property);
            if (value instanceof ASTNode child) children.add(child);
            else if (value instanceof List<?> list) for (var child : list) if (child instanceof ASTNode c) children.add(c);
        }
        return children;
    }

    /** A local the program counts: declared by a statement or an expression of a body, or as a loop's variable. */
    public record Local(ASTNode declaration, String name, String type, int nameStart, int start, int end) {

        /** What of the DOM is where the program has the local: a declarator, or a loop variable's name. */
        public Class<? extends ASTNode> domKind() {
            return declaration instanceof SingleVariableDeclaration ? org.eclipse.jdt.core.dom.SimpleName.class : declaration.getClass();
        }
    }

    /**
     * The locals a method or constructor declares in its own body, in the order they are written, a lambda's in it too
     * (not its parameters); those of a class inside it are theirs.
     */
    public static List<Local> locals(MethodDeclaration callable, String text) {
        var found = new ArrayList<Local>();
        if (callable.getBody() == null) return found;
        callable.getBody().accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment fragment) {
                var type = switch (fragment.getParent()) {
                    case VariableDeclarationStatement s -> s.getType();
                    case VariableDeclarationExpression e -> e.getType();
                    default -> null; // a field of a class inside, a lambda's parameter
                };
                if (type != null && callableOf(fragment) == callable) {
                    found.add(new Local(fragment, fragment.getName().getIdentifier(), written(type, fragment.getExtraDimensions()),
                            fragment.getName().getStartPosition(), fragment.getStartPosition(), end(fragment)));
                }
                return true;
            }

            @Override
            public boolean visit(EnhancedForStatement loop) {
                var variable = loop.getParameter();
                if (callableOf(loop) == callable) {
                    found.add(new Local(variable, variable.getName().getIdentifier(), written(variable.getType(), variable.getExtraDimensions()),
                            variable.getName().getStartPosition(), variable.getName().getStartPosition(), end(variable.getName())));
                }
                return true;
            }
        });
        found.sort(Comparator.comparingInt(Local::nameStart));
        return found;
    }

    /** The method or constructor a node is part of: the nearest, of whatever type (a class inside a method has its own). */
    public static MethodDeclaration callableOf(ASTNode node) {
        for (var at = node.getParent(); at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) return method;
            if (at instanceof AbstractTypeDeclaration) return null;
        }
        return null;
    }

    /** The type with a qualified name a node is in: one inside a method, or an anonymous one, is passed through. */
    public static AbstractTypeDeclaration typeOf(ASTNode node) {
        for (var at = node.getParent(); at != null; at = at.getParent()) {
            if (at instanceof AbstractTypeDeclaration type && !local(type)) return type;
        }
        return null;
    }

    /** Whether a type is declared inside a method, or inside one that is. */
    public static boolean local(AbstractTypeDeclaration type) {
        for (ASTNode at = type; at != null; at = at.getParent()) {
            if (at instanceof TypeDeclarationStatement || at instanceof AnonymousClassDeclaration) return true;
        }
        return false;
    }

    /** Whether a lambda's parameter. */
    public static boolean lambdaParameter(ASTNode declaration) {
        return declaration.getParent() instanceof LambdaExpression;
    }

    /** A parameter as the program has it. */
    public static String written(SingleVariableDeclaration parameter) {
        return written(parameter.getType(), parameter.getExtraDimensions());
    }

    /** What a member of an annotation type is: no element of the program. */
    public static boolean annotationMember(Object member) {
        return member instanceof AnnotationTypeMemberDeclaration;
    }

    /** Whether a record. */
    public static boolean record(AbstractTypeDeclaration type) {
        return type instanceof RecordDeclaration;
    }

    /**
     * A use as the program has always had it: what JavaParser called it ({@code category}: a name, a field access, a
     * call, a type, a new object, a constructor called first, a method reference, in that order where two start at
     * one place), where it is, its name and where the name is written (where the compiler's binding is kept), what it
     * is made on, and, for the type of a field or a local declared with others, which of them it is for.
     */
    public record Use(int category, ASTNode node, int start, int end, String name, int nameStart, int nameEnd, ASTNode scope,
                      boolean prefix, boolean typeExpression, org.eclipse.jdt.core.dom.VariableDeclarationFragment of, int order) {
    }

    public static final int NAME = 0, FIELD_ACCESS = 1, CALL = 2, TYPE = 3, NEW = 4, CONSTRUCTOR_CALL = 5, METHOD_REFERENCE = 6;

    /** The uses of a unit, in the order the program numbers them. */
    public static List<Use> uses(CompilationUnit unit) {
        var uses = new ArrayList<Use>();
        unit.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            private org.eclipse.jdt.core.dom.VariableDeclarationFragment of; // whose declared type is being read

            private void add(int category, ASTNode node, String name, ASTNode nameNode, ASTNode scope, boolean prefix, boolean typeExpression) {
                uses.add(new Use(category, node, node.getStartPosition(), end(node), name, nameNode.getStartPosition(), end(nameNode),
                        scope, prefix, typeExpression, of, uses.size()));
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.Javadoc doc) {
                return false;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.SimpleName name) {
                if (expression(name)) add(NAME, name, name.getIdentifier(), name, null, false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.QualifiedName name) {
                if (expression(name)) add(FIELD_ACCESS, name, name.getName().getIdentifier(), name.getName(), name.getQualifier(), false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.FieldAccess access) {
                add(FIELD_ACCESS, access, access.getName().getIdentifier(), access.getName(), access.getExpression(), false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.SuperFieldAccess access) {
                add(FIELD_ACCESS, access, access.getName().getIdentifier(), access.getName(), access, false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.MethodInvocation call) {
                add(CALL, call, call.getName().getIdentifier(), call.getName(), call.getExpression(), false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.SuperMethodInvocation call) {
                add(CALL, call, call.getName().getIdentifier(), call.getName(), call, false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.ClassInstanceCreation creation) {
                var name = typeName(creation.getType());
                if (name != null) add(NEW, creation, simple(name), last(name), null, false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.ConstructorInvocation call) {
                add(CONSTRUCTOR_CALL, call, "this", call, null, false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.SuperConstructorInvocation call) {
                add(CONSTRUCTOR_CALL, call, "super", call, null, false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.ExpressionMethodReference reference) {
                add(METHOD_REFERENCE, reference, reference.getName().getIdentifier(), reference.getName(), reference.getExpression(), false, false);
                if (reference.getExpression() instanceof org.eclipse.jdt.core.dom.Name name) { // JavaParser takes it for a type
                    named(name, name, true);
                    if (reference.typeArguments().isEmpty()) {
                        reference.getName().accept(this);
                        return false;
                    }
                }
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.TypeMethodReference reference) {
                add(METHOD_REFERENCE, reference, reference.getName().getIdentifier(), reference.getName(), reference.getType(), false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.SuperMethodReference reference) {
                add(METHOD_REFERENCE, reference, reference.getName().getIdentifier(), reference.getName(), reference, false, false);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.CreationReference reference) {
                var end = end(reference);
                uses.add(new Use(METHOD_REFERENCE, reference, reference.getStartPosition(), end, "new", end - 2, end, reference.getType(),
                        false, false, of, uses.size()));
                return true;
            }

            @Override
            public boolean visit(SimpleType type) {
                if (type.isVar() || type.getLocationInParent() == ParameterizedType.TYPE_PROPERTY) return true;
                named(type, type.getName(), false);
                return false;
            }

            @Override
            public boolean visit(ParameterizedType type) {
                if (type.getType() instanceof SimpleType raw) {
                    named(type, raw.getName(), false);
                    for (var argument : type.typeArguments()) ((ASTNode) argument).accept(this);
                    return false;
                }
                return true;
            }

            @Override
            public boolean visit(QualifiedType type) {
                add(TYPE, type, type.getName().getIdentifier(), type.getName(), null, false, false);
                return true;
            }

            @Override
            public boolean visit(NameQualifiedType type) {
                add(TYPE, type, type.getName().getIdentifier(), type.getName(), null, false, false);
                return false;
            }

            /** A type written as a name: {@code a.b.C} is C, and b and a before it, as JavaParser has them. */
            private void named(ASTNode type, org.eclipse.jdt.core.dom.Name name, boolean typeExpression) {
                add(TYPE, type, simple(name), last(name), null, false, typeExpression);
                for (var prefix = name instanceof org.eclipse.jdt.core.dom.QualifiedName q ? q.getQualifier() : null; prefix != null;
                     prefix = prefix instanceof org.eclipse.jdt.core.dom.QualifiedName q ? q.getQualifier() : null) {
                    add(TYPE, prefix, simple(prefix), last(prefix), null, true, typeExpression);
                }
            }

            @Override
            public boolean visit(FieldDeclaration field) {
                return declaredWithOthers(field, field.getType(), field.fragments());
            }

            @Override
            public boolean visit(VariableDeclarationStatement statement) {
                return declaredWithOthers(statement, statement.getType(), statement.fragments());
            }

            @Override
            public boolean visit(VariableDeclarationExpression expression) {
                return declaredWithOthers(expression, expression.getType(), expression.fragments());
            }

            /** JavaParser gives each variable its own type: one declared with others is read once for each. */
            private boolean declaredWithOthers(ASTNode declaration, Type type, List<?> fragments) {
                for (var property : declaration.structuralPropertiesForType()) {
                    if (property instanceof org.eclipse.jdt.core.dom.ChildListPropertyDescriptor list && list.getId().equals("modifiers")) {
                        for (var modifier : (List<?>) declaration.getStructuralProperty(list)) ((ASTNode) modifier).accept(this);
                    }
                }
                for (var fragment : fragments) {
                    of = (VariableDeclarationFragment) fragment;
                    type.accept(this);
                    of = null;
                    ((ASTNode) fragment).accept(this);
                }
                return false;
            }
        });
        uses.sort(Comparator.comparingInt(Use::start).thenComparingInt(Use::category).thenComparingInt(Use::order));
        return uses;
    }

    /** Whether a name is a value, as JavaParser has names: not a type's, a declaration's, a label, or a member's. */
    static boolean expression(org.eclipse.jdt.core.dom.Name name) {
        var location = name.getLocationInParent();
        if (location == org.eclipse.jdt.core.dom.QualifiedName.QUALIFIER_PROPERTY) {
            return expression((org.eclipse.jdt.core.dom.Name) name.getParent());
        }
        if (location == org.eclipse.jdt.core.dom.ExpressionMethodReference.EXPRESSION_PROPERTY) return false;
        if (location == LambdaExpression.BODY_PROPERTY) return true; // a lambda's body that is an expression
        if (location instanceof org.eclipse.jdt.core.dom.ChildPropertyDescriptor child) return child.getChildType() == org.eclipse.jdt.core.dom.Expression.class;
        if (location instanceof org.eclipse.jdt.core.dom.ChildListPropertyDescriptor list) return list.getElementType() == org.eclipse.jdt.core.dom.Expression.class;
        return false;
    }

    /** The name a written type is named by, or null. */
    public static org.eclipse.jdt.core.dom.Name typeName(Type type) {
        return switch (type) {
            case SimpleType simple -> simple.getName();
            case ParameterizedType parameterized -> typeName(parameterized.getType());
            default -> null;
        };
    }

    public static String simple(ASTNode name) {
        return name instanceof org.eclipse.jdt.core.dom.QualifiedName q ? q.getName().getIdentifier()
                : name instanceof org.eclipse.jdt.core.dom.SimpleName n ? n.getIdentifier() : name.toString();
    }

    public static ASTNode last(ASTNode name) {
        return name instanceof org.eclipse.jdt.core.dom.QualifiedName q ? q.getName() : name;
    }

    /** What a file declares, as text to compare with what it declared: see {@link ide.java.syntax.Shape}. */
    public static ide.java.syntax.Shape shape(ide.java.text.Unit unit) {
        var text = unit.text();
        var types = new java.util.LinkedHashMap<String, String>();
        var members = new java.util.LinkedHashMap<String, String>();
        var tree = unit.dom();
        for (var type : types(tree)) {
            var owner = qualified(type) + "#";
            var body = text.indexOf('{', end(type.getName()));
            types.put(owner, text.substring(start(type, text), Math.max(start(type, text), body)).strip());
            for (var member : type.bodyDeclarations()) {
                switch (member) {
                    case FieldDeclaration field -> {
                        var declared = text.substring(start(field, text), end(field.getType()) + 1);
                        for (var fragment : field.fragments()) {
                            var variable = (VariableDeclarationFragment) fragment;
                            members.put(owner + variable.getName().getIdentifier(), declared + " " + variable.getName().getIdentifier()
                                    + "[]".repeat(variable.getExtraDimensions()));
                        }
                    }
                    case MethodDeclaration method -> {
                        var signature = new StringBuilder(method.getName().getIdentifier()).append('(');
                        for (var i = 0; i < method.parameters().size(); i++) {
                            if (i > 0) signature.append(',');
                            var parameter = (SingleVariableDeclaration) method.parameters().get(i);
                            signature.append(text, parameter.getType().getStartPosition(), end(parameter.getType()) + 1);
                        }
                        signature.append(')');
                        var upTo = method.getBody() == null ? end(method) + 1 : method.getBody().getStartPosition();
                        members.put(owner + signature, text.substring(start(method, text), upTo).strip());
                    }
                    case AnnotationTypeMemberDeclaration element -> members.put(owner + element.getName().getIdentifier() + "()", textOf(element, text));
                    default -> {
                        // nested types are types of the file; initializers are bodies
                    }
                }
            }
            if (type instanceof EnumDeclaration enumeration) {
                for (var constant : enumeration.enumConstants()) {
                    members.put(owner + ((org.eclipse.jdt.core.dom.EnumConstantDeclaration) constant).getName().getIdentifier(), "constant");
                }
            }
        }
        var imports = tree.imports().stream().map(i -> textOf((ASTNode) i, text)).sorted().toList();
        return new ide.java.syntax.Shape(Map.copyOf(types), Map.copyOf(members), imports);
    }
}
