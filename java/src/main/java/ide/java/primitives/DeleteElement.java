package ide.java.primitives;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import ide.java.syntax.Imports;
import ide.java.syntax.Lists;
import ide.java.syntax.Members;
import ide.java.text.EditSet;
import ide.program.Change;
import ide.program.Element;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import java.util.ArrayList;
import java.util.List;

/**
 * Deletes an element nothing references: a member's lines (its comment included), a parameter the body does not use
 * (and its argument in every call), or a type, with the imports that name it: a top-level type's file, a nested
 * type's lines. A member deleted into another leaves its uses to it: they name it, if it has another name, in the
 * same edit that removes the member, so they never bind something in between.
 */
final class DeleteElement {

    private DeleteElement() {
    }

    static Change apply(Primitive.Delete delete, Context c, Program before) {
        PrimitiveRejected.unlessMergeable(before, delete);
        var element = before.element(delete.element());
        if (element.kind() == Kind.PARAM) deleteParam(c, element);
        else if (element.kind().isType()) deleteType(c, before, element);
        else deleteMember(c, before, element, delete.into() == null ? null : before.element(delete.into()));
        return delete.intent(before, null);
    }

    private static void deleteMember(Context c, Program before, Element element, Element into) {
        var uses = before.referencesTo(element.id()).stream().filter(r -> !before.within(r.site(), element.id())).toList();
        if (into == null && !uses.isEmpty()) {
            throw new PrimitiveRejected("delete.referenced", element.qualified() + " is used by "
                    + uses.stream().map(r -> before.element(r.site()).qualified()).distinct().toList());
        }
        var declaration = c.declaration(element);
        if (declaration.node() instanceof FieldDeclaration field && field.getVariables().size() > 1) {
            throw new IllegalArgumentException("deleting one of several variables is not supported: " + element);
        }
        var edits = new EditSet();
        for (var use : uses) if (!use.name().equals(into.name())) Shared.renameUse(c, edits, c.use(use.id()), into.name());
        Members.remove(edits, declaration.unit(), declaration.node());
        c.sources().apply(edits);
        c.ids().forget(element.id());
    }

    private static void deleteType(Context c, Program before, Element type) {
        var uses = before.referencesTo(type.id()).stream().filter(r -> !before.within(r.site(), type.id()))
                .map(r -> before.element(r.site()).qualified()).distinct().toList();
        if (!uses.isEmpty()) throw new PrimitiveRejected("delete.referenced", type.qualified() + " is used by " + uses);
        var edits = new EditSet();
        for (var unit : c.sources().units()) Imports.of(unit, type.qualified()).ifPresent(i -> Imports.remove(edits, unit, i));
        var path = type.fact(Fact.FILE);
        if (path != null) {
            c.sources().change(edits.texts(), List.of(path));
        } else {
            var declaration = c.declaration(type);
            Members.remove(edits, declaration.unit(), declaration.node());
            c.sources().apply(edits);
        }
        c.ids().forget(type.id());
    }

    private static void deleteParam(Context c, Element param) {
        var method = c.program().element(param.parent());
        var declaration = c.declaration(method);
        CallableDeclaration<?> node = declaration.as(CallableDeclaration.class);
        var used = node.findAll(NameExpr.class, n -> n.getNameAsString().equals(param.name())).stream().anyMatch(n -> {
            try {
                return n.resolve().isParameter();
            } catch (RuntimeException unresolved) {
                return false;
            }
        });
        if (used) throw new PrimitiveRejected("delete.referenced", param.qualified() + " is used in the body of " + method.qualified());
        var names = new ArrayList<>(Shared.parameterNames(node));
        var index = names.indexOf(param.name());
        var edits = new EditSet();
        without(edits, declaration.unit(), node.getParameters(), index);
        for (var call : Shared.callsTo(c, method.id())) without(edits, c.sources().unitOf(call.node()), call.arguments(), index);
        names.remove(index);
        c.sources().apply(edits);
        c.ids().forget(param.id());
        Shared.resign(c, method, declaration.unit().path(), names);
    }

    private static void without(EditSet edits, ide.java.text.Unit unit, com.github.javaparser.ast.NodeList<? extends com.github.javaparser.ast.Node> items, int index) {
        var texts = new ArrayList<>(Lists.texts(unit, items));
        texts.remove(index);
        Lists.rewrite(edits, unit, items, texts);
    }

}
