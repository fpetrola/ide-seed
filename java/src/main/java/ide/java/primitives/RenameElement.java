package ide.java.primitives;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ide.java.semantics.Bodies;
import ide.java.syntax.Imports;
import ide.java.text.EditSet;
import ide.program.Address;
import ide.program.Change;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * Renames an element and every use that resolves to it, keeping its identity. A type also renames its constructors,
 * the single-type imports of it, and its file when the file is named after it. A local may take the name of one in a
 * block beside it: the locals of its method are numbered anew; one in scope with that name is the compiler's to refuse.
 */
final class RenameElement {

    private RenameElement() {
    }

    static Change apply(Primitive.Rename rename, Context c, Program before) {
        Shared.requireIdentifier(rename.name());
        var element = before.element(rename.element());
        if (element.kind().isType()) renameType(c, before, element, rename.name());
        else renameMember(c, before, element, rename.name());
        return rename.intent(before, null);
    }

    private static void renameMember(Context c, Program before, Element element, String name) {
        var renamed = c.ids().address(element.id()).withName(name);
        var local = element.kind() == Kind.LOCAL;
        if (!local && c.ids().has(renamed)) throw new PrimitiveRejected("projection.duplicate", c.ids().render(renamed) + " already exists");
        var declaration = c.declaration(element);
        var unit = declaration.unit();
        var edits = new EditSet();
        switch (declaration.node()) {
            case Parameter param -> edits.replace(unit, param.getName(), name);
            case FieldDeclaration field -> field.getVariables().stream().filter(v -> v.getNameAsString().equals(element.name()))
                    .forEach(v -> edits.replace(unit, v.getName(), name));
            case MethodDeclaration method -> edits.replace(unit, method.getName(), name);
            case VariableDeclarator variable -> edits.replace(unit, variable.getName(), name);
            default -> throw new IllegalArgumentException("unsupported rename: " + element);
        }
        for (var ref : before.referencesTo(element.id())) Shared.renameUse(c, edits, c.use(ref.id()), name);
        var written = local ? localsOnceRenamed(c, declaration.as(VariableDeclarator.class), element.id(), name) : null;
        c.sources().apply(edits);
        if (local) c.ids().placeLocals(element.parent(), written);
        else c.ids().rename(element.id(), name);
    }

    /** The locals of a local's method, in the order they are written, once the local has its new name. */
    private static SequencedMap<ElementId, String> localsOnceRenamed(Context c, VariableDeclarator variable, ElementId renamed, String name) {
        var written = new LinkedHashMap<ElementId, String>();
        for (var local : Bodies.locals(variable.findAncestor(CallableDeclaration.class).orElseThrow())) {
            var id = c.reader().local(local);
            written.put(id, id.equals(renamed) ? name : local.getNameAsString());
        }
        return written;
    }

    private static void renameType(Context c, Program before, Element type, String name) {
        var renamed = type.qualified().substring(0, type.qualified().lastIndexOf('.') + 1) + name;
        if (c.ids().has(new Address.Type(renamed))) throw new PrimitiveRejected("projection.duplicate", renamed + " already exists");
        var names = new ArrayList<Node>();
        var targets = new ArrayList<ElementId>(List.of(type.id()));
        before.children(type.id()).stream().filter(e -> e.kind() == Kind.CONSTRUCTOR).forEach(k -> targets.add(k.id()));
        for (var target : targets) {
            for (var ref : before.referencesTo(target)) {
                switch (c.use(ref.id())) {
                    case ClassOrInterfaceType t -> names.add(t.getName());
                    case ObjectCreationExpr creation -> names.add(creation.getType().getName());
                    case NameExpr receiver -> names.add(receiver);
                    case Node other -> throw new IllegalStateException("unexpected type use " + other);
                }
            }
        }
        TypeDeclaration<?> declaration = c.declaration(type).as(TypeDeclaration.class);
        declaration.getConstructors().forEach(k -> names.add(k.getName()));
        names.add(declaration.getName());
        var sources = c.sources();
        var edits = new EditSet();
        for (var node : names) edits.replace(sources.unitOf(node), node, name);
        for (var unit : sources.units()) Imports.of(unit, type.qualified()).ifPresent(i -> edits.replace(unit, i.getName(), renamed));
        sources.apply(edits);
        var path = type.fact(Fact.FILE);
        if (path != null && path.endsWith("/" + type.name() + ".java")) {
            sources.rename(path, path.substring(0, path.length() - (type.name() + ".java").length()) + name + ".java");
        }
        c.ids().rebindType(type.qualified(), renamed);
    }
}
