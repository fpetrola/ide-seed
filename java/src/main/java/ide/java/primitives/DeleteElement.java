package ide.java.primitives;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import ide.java.syntax.Imports;
import ide.java.syntax.Lists;
import ide.java.syntax.Members;
import ide.java.text.EditSet;
import ide.program.Change;
import ide.program.Element;
import ide.program.ElementId;
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
 * type's lines.
 */
final class DeleteElement {

    private DeleteElement() {
    }

    static Change apply(Primitive.Delete delete, Context c, Program before) {
        var element = before.element(delete.element());
        if (element.kind() == Kind.PARAM) deleteParam(c, element);
        else if (element.kind().isType()) deleteType(c, before, element);
        else deleteMember(c, before, element);
        return delete.intent(before, null);
    }

    private static void deleteMember(Context c, Program before, Element element) {
        var uses = before.referencesTo(element.id()).stream().map(r -> before.element(r.site()).qualified()).distinct().toList();
        if (!uses.isEmpty()) throw new PrimitiveRejected("delete.referenced", element.qualified() + " is used by " + uses);
        var declaration = c.declaration(element);
        if (declaration.node() instanceof FieldDeclaration field && field.getVariables().size() > 1) {
            throw new IllegalArgumentException("deleting one of several variables is not supported: " + element);
        }
        c.sources().apply(Shared.edits(e -> Members.remove(e, declaration.unit(), declaration.node())));
        c.ids().forget(element.id());
    }

    private static void deleteType(Context c, Program before, Element type) {
        var uses = before.referencesTo(type.id()).stream().filter(r -> !inside(before, r.site(), type.id()))
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
        var node = declaration.as(MethodDeclaration.class);
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
        for (var call : Shared.callsTo(c, method.id())) without(edits, c.sources().unitOf(call), call.getArguments(), index);
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

    private static boolean inside(Program program, ElementId id, ElementId container) {
        for (var e = program.element(id); ; e = program.element(e.parent())) {
            if (e.id().equals(container)) return true;
            if (e.parent() == null) return false;
        }
    }
}
