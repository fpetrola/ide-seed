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

import java.util.List;

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
        var value = set.attribute().equals("throws") ? uncovered(c, before, set.element(), set.value()) : set.value();
        var gone = set.attribute().equals("throws") ? untold(c, before, set.element(), value)
                : set.attribute().equals("static") && "false".equals(value) && before.element(set.element()).kind() == ide.program.Kind.FIELD
                ? objects(c, before, set.element()) : List.<Change.Dropped>of();
        set(c, set.element(), set.attribute(), value);
        var change = set.intent(before, null);
        for (var dropped : gone) change = change.expect(dropped);
        return change;
    }

    /**
     * A field made one each object has is used as the object's own where it was used through its type: {@code this.f}
     * where a name hides it. Where there is no object, the compiler says so.
     */
    private static List<Change.Dropped> objects(Context c, Program before, ElementId field) {
        var gone = new java.util.ArrayList<Change.Dropped>();
        var edits = new ide.java.text.EditSet();
        var name = before.element(field).name();
        for (var use : before.referencesTo(field)) {
            var scope = Shared.typeScope(c, before, use);
            if (scope == null) continue;
            var node = c.use(use.id());
            edits.replace(c.sources().unitOf(node), node, Shared.unscoped(c, use, name, "this"));
            var range = scope.getRange().orElseThrow();
            for (var r : before.referencesFrom(use.site())) {
                if (!r.mode().isLink() && c.use(r.id()).getRange().filter(range::contains).isPresent()) gone.add(new Change.Dropped(r.site(), r.target()));
            }
        }
        c.sources().apply(edits);
        return gone;
    }

    /**
     * What a callable is to declare it throws, without what another of them covers: a type it declares whose supertype
     * is among them goes, as that one says it (one not declared yet is taken as written, or of java.lang).
     */
    private static String uncovered(Context c, Program before, ElementId id, String value) {
        CallableDeclaration<?> callable = c.declaration(before.element(id)).as(CallableDeclaration.class);
        var types = java.util.Arrays.stream(value.split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList();
        var qualified = new java.util.HashMap<String, String>();
        var ancestors = new java.util.HashMap<String, java.util.Set<String>>();
        for (var thrown : callable.getThrownExceptions()) {
            if (!(thrown instanceof com.github.javaparser.ast.type.ClassOrInterfaceType written)) continue;
            var declaration = ide.java.semantics.Resolution.resolveDeclaration(written);
            if (declaration == null) continue;
            qualified.put(written.asString(), declaration.getQualifiedName());
            try {
                ancestors.put(written.asString(), declaration.getAllAncestors().stream().map(a -> a.getQualifiedName()).collect(java.util.stream.Collectors.toSet()));
            } catch (RuntimeException unknown) {
                // what it extends cannot be told: it stays
            }
        }
        java.util.function.Function<String, String> named = t -> t.contains(".") ? t : qualified.getOrDefault(t, "java.lang." + t);
        return String.join(", ", types.stream().filter(t -> types.stream().noneMatch(other -> other != t
                && ancestors.getOrDefault(t, java.util.Set.of()).contains(named.apply(other)))).toList());
    }

    /** The uses of the types a callable declares it throws that it will not any more: they go on purpose. */
    private static List<Change.Dropped> untold(Context c, Program before, ElementId id, String value) {
        CallableDeclaration<?> callable = c.declaration(before.element(id)).as(CallableDeclaration.class);
        var kept = java.util.Arrays.stream(value.split(",")).map(String::strip).map(t -> t.substring(t.lastIndexOf('.') + 1)).toList();
        var gone = new java.util.ArrayList<Change.Dropped>();
        for (var thrown : callable.getThrownExceptions()) {
            var written = thrown.asString();
            if (kept.contains(written.substring(written.lastIndexOf('.') + 1))) continue;
            var range = thrown.getRange().orElseThrow();
            for (var r : before.referencesFrom(id)) {
                if (c.use(r.id()).getRange().filter(range::contains).isPresent()) gone.add(new Change.Dropped(r.site(), r.target()));
            }
        }
        return gone;
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
            case "annotations" -> annotations(c, id, value);
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
        var listOf = ide.program.Body.listOf(type);
        var written = listOf != null ? WriteBody.listType(c, id, edits, unit, listOf) // said in no language
                : type.contains(".") && c.ids().has(new Address.Type(type)) ? Imports.nameable(edits, unit, type) : imported(c, edits, unit, type);
        switch (declaration.node()) {
            case FieldDeclaration field -> field.getVariables().forEach(v -> edits.replace(unit, v.getType(), written));
            case MethodDeclaration m -> edits.replace(unit, m.getType(), written);
            case Parameter param -> edits.replace(unit, param.getType(), written);
            case com.github.javaparser.ast.body.VariableDeclarator local -> { // a local declared alone: its declaration's type
                var declared = local.getParentNode().filter(d -> d instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr v
                        && v.getVariables().size() == 1).orElseThrow(() -> new ide.projection.PrimitiveRejected("set.unsupported",
                        element.qualified() + " is declared with others: its type is theirs too"));
                edits.replace(unit, ((com.github.javaparser.ast.expr.VariableDeclarationExpr) declared).getElementType(), written);
            }
            default -> throw new IllegalArgumentException("unsupported type change: " + element);
        }
        c.sources().apply(edits);
        if (method != null) Shared.resign(c, method, unit.path(), names);
    }

    /**
     * A type as written, each simple name in it the file cannot name yet imported when the program knows one type of
     * that name, of the workspace or a library one it names (java.lang's need none); of two, or none, it stays as it
     * is, a use that binds nothing.
     */
    private static String imported(Context c, EditSet edits, Unit unit, String type) {
        var names = new java.util.LinkedHashSet<String>();
        var token = java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]*").matcher(type);
        while (token.find()) {
            var start = token.start();
            if (start > 0 && type.charAt(start - 1) == '.') continue; // part of a qualified name
            names.add(token.group());
        }
        for (var name : names) {
            var known = c.program().elements().stream().filter(e -> e.kind().isType() && e.name().equals(name)
                    && (e.inLibrary() || e.parent() == null || c.program().element(e.parent()).kind() == Kind.PACKAGE)).map(e -> e.qualified()).distinct().toList();
            if (known.isEmpty() || known.stream().anyMatch(q -> q.equals("java.lang." + name) || Imports.names(unit, q))) continue;
            if (known.size() == 1 && known.getFirst().contains(".")) Imports.add(edits, unit, known.getFirst());
        }
        return type;
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
                .map(type -> type.contains(".") ? Imports.nameable(edits, unit, type) : imported(c, edits, unit, type)).toList();
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
    /**
     * What a declaration is annotated with, as written, replacing what it was: each on a line of its own above a type or
     * a member, before a parameter on its line; a qualified name imported. Empty: none.
     */
    private static void annotations(Context c, ElementId id, String value) {
        var declaration = c.declaration(c.program().element(id));
        var unit = declaration.unit();
        if (!(declaration.node() instanceof com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> annotated)) {
            throw new ide.projection.PrimitiveRejected("set.annotations", c.program().element(id).qualified() + " cannot be annotated here");
        }
        var edits = new EditSet();
        var written = new java.util.ArrayList<String>();
        for (var one : annotationsIn(value)) {
            var name = one.substring(1).split("\\(", 2)[0].strip();
            written.add(name.contains(".") ? "@" + Imports.nameable(edits, unit, name) + one.substring(1 + name.length()) : one);
        }
        var node = declaration.node();
        var separator = node instanceof Parameter ? " " : "\n" + unit.indentation(node.getBegin().orElseThrow().line);
        var text = String.join(separator, written);
        var old = annotated.getAnnotations();
        if (old.isEmpty()) {
            if (!written.isEmpty()) edits.insertAt(unit, node.getBegin().orElseThrow(), text + separator);
        } else {
            for (var i = 0; i < old.size(); i++) {
                var one = old.get(i);
                if (i == 0 && !written.isEmpty()) edits.replace(unit, one, text);
                else if (unit.line(one.getBegin().orElseThrow().line).strip().equals(unit.textOf(one))) edits.removeLines(unit, one.getBegin().orElseThrow().line, one.getEnd().orElseThrow().line);
                else edits.removeWithSpaceAfter(unit, one);
            }
        }
        c.sources().apply(edits);
    }

    /** Annotations as written one after another: each starts with an {@code @} outside quotes and parentheses. */
    private static List<String> annotationsIn(String value) {
        var found = new java.util.ArrayList<String>();
        var depth = 0;
        var quoted = false;
        var start = -1;
        for (var i = 0; i < value.length(); i++) {
            var ch = value.charAt(i);
            if (ch == '"' && (i == 0 || value.charAt(i - 1) != '\\')) quoted = !quoted;
            if (quoted) continue;
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (ch == '@' && depth == 0) {
                if (start >= 0) found.add(value.substring(start, i).strip());
                start = i;
            }
        }
        if (start >= 0) found.add(value.substring(start).strip());
        return found;
    }

    private static void initializer(Context c, ElementId id, String value) {
        var element = c.program().element(id);
        var declaration = c.declaration(element);
        var unit = declaration.unit();
        var variable = declaration.node() instanceof com.github.javaparser.ast.body.VariableDeclarator local ? local // a local's
                : declaration.as(FieldDeclaration.class).getVariables().stream()
                .filter(v -> v.getNameAsString().equals(element.name())).findFirst().orElseThrow();
        var edits = new EditSet();
        var local = element.kind() == Kind.LOCAL;
        if (local && variable.getInitializer().isPresent()) throw new ide.projection.PrimitiveRejected("set.initializer", element.qualified() + " has a value already");
        var written = ide.program.Body.names(value) ? WriteBody.value(c, local ? element.parent() : id, edits, unit, value, element.fact(ide.program.Fact.TYPE)) : value; // said in no language, or in Java
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
