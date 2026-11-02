package ide.java.primitives;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import ide.java.syntax.Imports;
import ide.java.syntax.Lists;
import ide.java.syntax.Modifiers;
import ide.java.text.EditSet;
import ide.java.text.Unit;
import ide.program.Address;
import ide.program.Change;
import ide.program.ElementId;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;

/**
 * Sets an attribute of an element, as text: its access or another modifier, an abstract method or class, the
 * superclass or the declared type as written, what a callable declares it throws, a body said in no language
 * ({@link WriteBody}), or an initial value.
 * {@link LinkType} writes extends clauses, interfaces and declared types with the same means.
 */
final class SetAttribute {

    private SetAttribute() {
    }

    static Change apply(Primitive.Set set, Context c, Program before) {
        set(c, set.element(), set.attribute(), set.value());
        return set.intent(before, null);
    }

    static void set(Context c, ElementId id, String attribute, String value) {
        switch (attribute) {
            case "visibility" -> modifiers(c, id, (edits, unit, node) -> Modifiers.visibility(edits, unit, node, value));
            case "static" -> modifiers(c, id, (edits, unit, node) -> Modifiers.flag(edits, unit, node, Modifier.Keyword.STATIC, Boolean.parseBoolean(value)));
            case "final" -> modifiers(c, id, (edits, unit, node) -> Modifiers.flag(edits, unit, node, Modifier.Keyword.FINAL, Boolean.parseBoolean(value)));
            case "abstract" -> setAbstract(c, id, Boolean.parseBoolean(value));
            case "superclass" -> superclass(c, id, value);
            case "type" -> type(c, id, value);
            case "throws" -> thrown(c, id, value);
            case "initializer" -> initializer(c, id, value);
            default -> throw new IllegalArgumentException("unsupported attribute: " + attribute);
        }
    }

    private interface ModifierEdit {
        void make(EditSet edits, Unit unit, NodeWithModifiers<?> declaration);
    }

    private static void modifiers(Context c, ElementId id, ModifierEdit edit) {
        var declaration = c.declaration(c.program().element(id));
        c.sources().apply(Shared.edits(e -> edit.make(e, declaration.unit(), (NodeWithModifiers<?>) declaration.node())));
    }

    /** A method without body (in an interface just the signature, in a class an abstract method), or an abstract class. */
    private static void setAbstract(Context c, ElementId id, boolean on) {
        var element = c.program().element(id);
        if (!on) throw new IllegalArgumentException("unsupported: abstract=false on " + element);
        if (element.kind() == Kind.CLASS) {
            modifiers(c, id, (edits, unit, node) -> Modifiers.flag(edits, unit, node, Modifier.Keyword.ABSTRACT, true));
            return;
        }
        if (element.kind() != Kind.METHOD) throw new IllegalArgumentException("unsupported: abstract on " + element);
        var declaration = c.declaration(element);
        var method = declaration.as(MethodDeclaration.class);
        var unit = declaration.unit();
        var edits = new EditSet();
        if (c.program().element(element.parent()).kind() != Kind.INTERFACE) {
            Modifiers.flag(edits, unit, method, Modifier.Keyword.ABSTRACT, true);
        }
        var body = method.getBody().orElseThrow();
        var signatureEnd = unit.previousToken(body.getBegin().orElseThrow());
        edits.replace(unit, Unit.after(signatureEnd), body.getEnd().orElseThrow(), ";");
        var before = c.program();
        c.sources().apply(edits);
        Shared.forgetBody(c, before, id);
    }

    /** The extends clause: a qualified name of the same package written simply, any other text as it is. */
    static void superclass(Context c, ElementId id, String superclass) {
        var declaration = c.declaration(c.program().element(id));
        var type = declaration.as(ClassOrInterfaceDeclaration.class);
        var unit = declaration.unit();
        var name = simpleIfSamePackage(unit, superclass);
        var edits = new EditSet();
        if (type.getExtendedTypes().isEmpty()) edits.insertAt(unit, afterNameAndTypeParameters(unit, type), " extends " + name);
        else edits.replace(unit, type.getExtendedTypes(0), name); // the whole type: its arguments belonged to the old one
        c.sources().apply(edits);
    }

    /** One more interface a class implements, or an interface extends. */
    static void implement(Context c, ElementId id, String qualified) {
        var declaration = c.declaration(c.program().element(id));
        var type = declaration.as(ClassOrInterfaceDeclaration.class);
        var unit = declaration.unit();
        var name = simpleIfSamePackage(unit, qualified);
        var list = type.isInterface() ? type.getExtendedTypes() : type.getImplementedTypes();
        var edits = new EditSet();
        if (list.isNonEmpty()) edits.insertAfter(unit, list.getLast().orElseThrow(), ", " + name);
        else if (type.getExtendedTypes().isNonEmpty()) edits.insertAfter(unit, type.getExtendedTypes().getLast().orElseThrow(), " implements " + name);
        else edits.insertAt(unit, afterNameAndTypeParameters(unit, type), (type.isInterface() ? " extends " : " implements ") + name);
        c.sources().apply(edits);
    }

    /** The declared type, as written; a workspace type given by qualified name is imported and written simply. */
    static void type(Context c, ElementId id, String type) {
        var element = c.program().element(id);
        var declaration = c.declaration(element);
        var unit = declaration.unit();
        // a parameter's type is part of its method's address: what the method is, is read before the text changes
        var method = element.kind() == Kind.PARAM ? c.program().element(element.parent()) : null;
        var names = method == null ? null : Shared.parameterNames(c.declaration(method).as(CallableDeclaration.class));
        var edits = new EditSet();
        var written = type.contains(".") && c.ids().has(new Address.Type(type)) ? Imports.nameable(edits, unit, type) : type;
        switch (declaration.node()) {
            case FieldDeclaration field -> field.getVariables().forEach(v -> edits.replace(unit, v.getType(), written));
            case MethodDeclaration m -> edits.replace(unit, m.getType(), written);
            case Parameter param -> edits.replace(unit, param.getType(), written);
            default -> throw new IllegalArgumentException("unsupported type change: " + element);
        }
        c.sources().apply(edits);
        if (method != null) Shared.resign(c, method, unit.path(), names);
    }

    /**
     * What a method or constructor declares it throws: the types given, in order, each written as the file can name it
     * (a qualified one imported); none takes the clause away.
     */
    private static void thrown(Context c, ElementId id, String value) {
        var declaration = c.declaration(c.program().element(id));
        CallableDeclaration<?> callable = declaration.as(CallableDeclaration.class);
        var unit = declaration.unit();
        var edits = new EditSet();
        var types = java.util.Arrays.stream(value.split(",")).map(String::strip).filter(type -> !type.isEmpty())
                .map(type -> type.contains(".") ? Imports.nameable(edits, unit, type) : type).toList();
        var thrown = callable.getThrownExceptions();
        var parameters = callable.getParameters();
        var close = unit.next(')', parameters.isNonEmpty() ? parameters.getLast().orElseThrow().getEnd().orElseThrow()
                : unit.next('(', callable.getName().getEnd().orElseThrow()));
        if (thrown.isNonEmpty() && !types.isEmpty()) Lists.rewrite(edits, unit, thrown, types);
        else if (thrown.isNonEmpty()) edits.replace(unit, Unit.after(close), thrown.getLast().orElseThrow().getEnd().orElseThrow(), "");
        else if (!types.isEmpty()) edits.insertAt(unit, Unit.after(close), " throws " + String.join(", ", types));
        c.sources().apply(edits);
    }

    /** The initial value of a field: as Java writes it, or said in no language ({@code #{method:4}()}). */
    private static void initializer(Context c, ElementId id, String value) {
        var element = c.program().element(id);
        var declaration = c.declaration(element);
        var unit = declaration.unit();
        var variable = declaration.as(FieldDeclaration.class).getVariables().stream()
                .filter(v -> v.getNameAsString().equals(element.name())).findFirst().orElseThrow();
        var edits = new EditSet();
        var written = ide.program.Body.names(value) ? WriteBody.value(c, id, edits, unit, value) : value; // said in no language, or in Java
        if (variable.getInitializer().isPresent()) edits.replace(unit, variable.getInitializer().get(), written);
        else edits.insertAfter(unit, variable.getName(), " = " + written);
        c.sources().apply(edits);
    }

    private static String simpleIfSamePackage(Unit unit, String qualified) {
        var pkg = Imports.packageOf(unit);
        return !pkg.isEmpty() && qualified.startsWith(pkg + ".") && qualified.indexOf('.', pkg.length() + 1) < 0
                ? qualified.substring(pkg.length() + 1) : qualified;
    }

    private static Position afterNameAndTypeParameters(Unit unit, TypeDeclaration<?> type) {
        if (!(type instanceof ClassOrInterfaceDeclaration c) || c.getTypeParameters().isEmpty()) {
            return Unit.after(type.getName().getEnd().orElseThrow());
        }
        return Unit.after(unit.next('>', c.getTypeParameters().getLast().orElseThrow().getEnd().orElseThrow()));
    }
}
