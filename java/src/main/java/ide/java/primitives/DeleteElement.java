package ide.java.primitives;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ide.java.semantics.Bodies;
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
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Deletes an element nothing references: a member's lines (its comment included), a parameter the body does not use
 * (and its argument in every call), a local declared alone on its lines whose value has no effects, or a type, with
 * the imports that name it: a top-level type's file, a nested type's lines. A member deleted into another leaves its uses to it: they name it, if it has another name, in the
 * same edit that removes the member, so they never bind something in between.
 */
final class DeleteElement {

    private DeleteElement() {
    }

    static Change apply(Primitive.Delete delete, Context c, Program before) {
        PrimitiveRejected.unlessMergeable(before, delete);
        var element = before.element(delete.element());
        var change = delete.intent(before, null);
        if (element.kind() == Kind.LOCAL) for (var gone : deleteLocal(c, before, element)) change = change.expect(gone);
        else if (element.kind() == Kind.PARAM) deleteParam(c, element);
        else if (element.kind().isType()) deleteType(c, before, element);
        else deleteMember(c, before, element, delete.into() == null ? null : before.element(delete.into()));
        return change;
    }

    /**
     * A local nothing reads, declared alone on its lines, whose value has no effects (they would no longer happen).
     * The uses its declaration makes are gone, and the method's other locals of its name close ranks.
     */
    private static List<Change.Dropped> deleteLocal(Context c, Program before, Element local) {
        var uses = before.referencesTo(local.id());
        if (!uses.isEmpty()) throw new PrimitiveRejected("delete.referenced", local.qualified() + " is read " + uses.size() + " times");
        var declaration = c.declaration(local);
        var variable = declaration.as(VariableDeclarator.class);
        var statement = variable.getParentNode().filter(d -> ((VariableDeclarationExpr) d).getVariables().size() == 1)
                .flatMap(Node::getParentNode).filter(ExpressionStmt.class::isInstance).orElse(null);
        var unit = declaration.unit();
        if (statement == null || !unit.lines(statement.getBegin().orElseThrow().line, statement.getEnd().orElseThrow().line).strip()
                .equals(unit.textOf(statement).strip())) {
            throw new PrimitiveRejected("delete.unsupported", local.qualified() + " is not declared alone on its lines");
        }
        var effect = variable.getInitializer().map(Bodies::effect).orElse(null);
        if (effect != null) throw new PrimitiveRejected("delete.effects", "'" + effect + "' would no longer run");
        var range = statement.getRange().orElseThrow();
        var gone = before.referencesFrom(local.parent()).stream().filter(r -> c.use(r.id()).getRange().filter(range::contains).isPresent())
                .map(r -> new Change.Dropped(r.site(), r.target())).toList();
        CallableDeclaration<?> callable = variable.findAncestor(CallableDeclaration.class).orElseThrow();
        var others = new LinkedHashMap<ElementId, String>();
        for (var other : Bodies.locals(callable)) {
            if (other != variable) others.put(c.reader().local(other), other.getNameAsString());
        }
        var place = Bodies.place(Bodies.statements(callable), statement); // a statement of the body, or inside one
        var own = before.children(local.parent()).stream().filter(e -> e.kind() == Kind.STATEMENT).map(Element::id).toList();
        var edits = new EditSet();
        Members.remove(edits, unit, statement);
        c.sources().apply(edits);
        c.ids().forget(local.id());
        c.ids().placeLocals(local.parent(), others);
        if (place >= 0) {
            c.ids().forget(own.get(place));
            c.ids().placeStatements(local.parent(), own.stream().filter(s -> !s.equals(own.get(place))).toList());
        }
        return gone;
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
