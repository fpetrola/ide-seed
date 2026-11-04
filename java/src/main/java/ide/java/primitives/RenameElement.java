package ide.java.primitives;

import ide.java.semantics.DomSyntax;
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
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.HashMap;
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
        var declared = declaration.dom();
        switch (declared) {
            case SingleVariableDeclaration param -> edits.replace(unit, param.getName(), name);
            case FieldDeclaration field -> {
                for (var fragment : field.fragments()) {
                    var variable = (VariableDeclarationFragment) fragment;
                    if (variable.getName().getIdentifier().equals(element.name())) edits.replace(unit, variable.getName(), name);
                }
            }
            case MethodDeclaration method -> edits.replace(unit, method.getName(), name);
            case VariableDeclarationFragment variable -> edits.replace(unit, variable.getName(), name);
            case SimpleName variable -> edits.replace(unit, variable, name); // a loop's variable
            default -> throw new IllegalArgumentException("unsupported rename: " + element);
        }
        for (var ref : before.referencesTo(element.id())) Shared.renameUse(c, edits, c.useDom(ref.id()), name);
        var written = local ? localsOnceRenamed(c, declared, element, name) : null;
        c.sources().apply(edits);
        if (local) c.ids().placeLocals(element.parent(), written);
        else c.ids().rename(element.id(), name);
    }

    /** The locals of a local's method, in the order they are written, once the local has its new name. */
    private static SequencedMap<ElementId, String> localsOnceRenamed(Context c, ASTNode declared, Element renamed, String name) {
        var written = new LinkedHashMap<ElementId, String>();
        var method = DomSyntax.callableOf(declared);
        var seen = new HashMap<String, Integer>();
        for (var local : DomSyntax.locals(method, c.sources().unitOf(declared).text())) {
            var id = c.ids().get(new Address.Local(renamed.parent(), local.name(), seen.merge(local.name(), 1, Integer::sum) - 1));
            written.put(id, id.equals(renamed.id()) ? name : local.name());
        }
        return written;
    }

    private static void renameType(Context c, Program before, Element type, String name) {
        var renamed = type.qualified().substring(0, type.qualified().lastIndexOf('.') + 1) + name;
        if (c.ids().has(new Address.Type(renamed))) throw new PrimitiveRejected("projection.duplicate", renamed + " already exists");
        var names = new ArrayList<ASTNode>();
        var targets = new ArrayList<ElementId>(List.of(type.id()));
        before.children(type.id()).stream().filter(e -> e.kind() == Kind.CONSTRUCTOR).forEach(k -> targets.add(k.id()));
        for (var target : targets) {
            for (var ref : before.referencesTo(target)) names.add(typeNamed(c.useDom(ref.id())));
        }
        var declaration = c.declaration(type).domAs(AbstractTypeDeclaration.class);
        for (var member : declaration.bodyDeclarations()) {
            if (member instanceof MethodDeclaration constructor && constructor.isConstructor()) names.add(constructor.getName());
        }
        names.add(declaration.getName());
        var sources = c.sources();
        var edits = new EditSet();
        for (var node : names) edits.replace(sources.unitOf(node), node, name);
        for (var unit : sources.units()) {
            if (!unit.text().contains(type.name())) continue; // no import of it without its name
            for (var each : unit.dom().imports()) {
                var imported = (ImportDeclaration) each;
                if (!imported.isOnDemand() && !imported.isStatic() && imported.getName().getFullyQualifiedName().equals(type.qualified())) {
                    edits.replace(unit, imported.getName(), renamed);
                }
            }
        }
        sources.apply(edits);
        var path = type.fact(Fact.FILE);
        if (path != null && path.endsWith("/" + type.name() + ".java")) {
            sources.rename(path, path.substring(0, path.length() - (type.name() + ".java").length()) + name + ".java");
        }
        c.ids().rebindType(type.qualified(), renamed);
    }

    /** The name a use of a type writes it with: the last of {@code a.B}, of {@code B<C>}, of {@code new B()}. */
    private static ASTNode typeNamed(ASTNode use) {
        return switch (use) {
            case SimpleType simple -> DomSyntax.last(simple.getName());
            case ParameterizedType parameterized -> typeNamed(parameterized.getType());
            case QualifiedType qualified -> qualified.getName();
            case ClassInstanceCreation creation -> typeNamed(creation.getType());
            case Name named -> DomSyntax.last(named);
            default -> throw new IllegalStateException("unexpected type use " + use);
        };
    }
}
