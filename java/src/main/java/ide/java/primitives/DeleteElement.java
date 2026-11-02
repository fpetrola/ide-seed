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
 * (and its argument in every call), a local declared alone on its lines whose value has no effects, a statement alone
 * on its lines with the locals it declares, or a type, with the imports that name it: a top-level type's file, a
 * nested type's lines. A member deleted into another leaves its uses to it: they name it, if it has another name, in the
 * same edit that removes the member, so they never bind something in between.
 */
final class DeleteElement {

    private DeleteElement() {
    }

    static Change apply(Primitive.Delete delete, Context c, Program before) {
        PrimitiveRejected.unlessMergeable(before, delete);
        var element = before.element(delete.element());
        var change = delete.intent(before, null);
        if (element.kind() == Kind.LOCAL && delete.into() != null) for (var gone : mergeLocal(c, before, element)) change = change.expect(gone);
        else if (element.kind() == Kind.LOCAL) for (var gone : deleteLocal(c, before, element)) change = change.expect(gone);
        else if (element.kind() == Kind.STATEMENT) deleteStatement(c, before, element);
        else if (element.kind() == Kind.PARAM && delete.into() != null) for (var gone : thisParam(c, before, element, delete.into())) change = change.expect(gone);
        else if (element.kind() == Kind.PARAM) for (var gone : deleteParam(c, before, element)) change = change.expect(gone);
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
        if (statement == null || !ide.java.syntax.Statements.alone(unit, statement)) {
            throw new PrimitiveRejected("delete.unsupported", local.qualified() + " is not declared alone on its lines");
        }
        var effect = variable.getInitializer().map(value -> Bodies.effect(value, Shared.reads(c, before))).orElse(null);
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

    /**
     * A local that goes into the field of its name: its declaration gives the field the value it was given, there, and
     * says nothing if it gave none; its uses are the field's (the intent says so), and the method's other locals of its
     * name close ranks.
     */
    private static List<Change.Dropped> mergeLocal(Context c, Program before, Element local) {
        var declaration = c.declaration(local);
        var variable = declaration.as(VariableDeclarator.class);
        var statement = variable.getParentNode().filter(d -> ((VariableDeclarationExpr) d).getVariables().size() == 1)
                .flatMap(Node::getParentNode).filter(ExpressionStmt.class::isInstance).orElse(null);
        var unit = declaration.unit();
        if (statement == null || !ide.java.syntax.Statements.alone(unit, statement)) {
            throw new PrimitiveRejected("delete.unsupported", local.qualified() + " is not declared alone on its lines");
        }
        CallableDeclaration<?> callable = variable.findAncestor(CallableDeclaration.class).orElseThrow();
        var others = new LinkedHashMap<ElementId, String>();
        for (var other : Bodies.locals(callable)) {
            if (other != variable) others.put(c.reader().local(other), other.getNameAsString());
        }
        var place = Bodies.place(Bodies.statements(callable), statement);
        var own = before.children(local.parent()).stream().filter(e -> e.kind() == Kind.STATEMENT).map(Element::id).toList();
        var type = ((VariableDeclarationExpr) variable.getParentNode().orElseThrow()).getElementType().getRange().orElseThrow();
        var gone = before.referencesFrom(local.parent()).stream() // the type it was declared with is named no more
                .filter(r -> c.use(r.id()).getRange().filter(type::contains).isPresent())
                .map(r -> new Change.Dropped(r.site(), r.target())).toList();
        var edits = new EditSet();
        var value = variable.getInitializer().orElse(null);
        if (value != null) edits.replace(unit, statement, local.name() + " = " + unit.textOf(value) + ";");
        else Members.remove(edits, unit, statement);
        c.sources().apply(edits);
        c.ids().forget(local.id());
        c.ids().placeLocals(local.parent(), others);
        if (value == null && place >= 0) {
            c.ids().forget(own.get(place));
            c.ids().placeStatements(local.parent(), own.stream().filter(s -> !s.equals(own.get(place))).toList());
        }
        return gone;
    }

    /**
     * A statement alone on its lines, with the locals it declares: the uses made in it go with it (the intent says so),
     * and the statements and locals after it close ranks. What read its locals afterwards binds nothing, which the
     * verifier sees.
     */
    private static void deleteStatement(Context c, Program before, Element statement) {
        var declaration = c.declaration(statement);
        var unit = declaration.unit();
        var node = declaration.node();
        if (!ide.java.syntax.Statements.alone(unit, node)) throw new PrimitiveRejected("delete.unsupported", statement.qualified() + " shares its lines");
        CallableDeclaration<?> callable = node.findAncestor(CallableDeclaration.class).orElseThrow();
        var gone = new ArrayList<ElementId>();
        var others = new LinkedHashMap<ElementId, String>();
        for (var local : Bodies.locals(callable)) {
            if (node.isAncestorOf(local)) gone.add(c.reader().local(local));
            else others.put(c.reader().local(local), local.getNameAsString());
        }
        var left = before.children(statement.parent()).stream().filter(e -> e.kind() == Kind.STATEMENT && !e.id().equals(statement.id()))
                .map(Element::id).toList();
        var edits = new EditSet();
        Members.remove(edits, unit, node);
        c.sources().apply(edits);
        c.ids().forget(statement.id());
        gone.forEach(c.ids()::forget);
        c.ids().placeLocals(statement.parent(), others);
        c.ids().placeStatements(statement.parent(), left);
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

    /**
     * A parameter its body does not use, and its argument in every call: what an argument used is used no more there
     * (it is gone on purpose); one that has effects is refused, since they would no longer happen.
     */
    private static List<Change.Dropped> deleteParam(Context c, Program before, Element param) {
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
        var gone = new ArrayList<Change.Dropped>();
        without(edits, declaration.unit(), node.getParameters(), index);
        for (var call : Shared.callsTo(c, method.id())) {
            if (index < call.arguments().size()) {
                var argument = call.arguments().get(index);
                var effect = Bodies.effect(argument, Shared.reads(c, before));
                if (effect != null) throw new PrimitiveRejected("delete.effects", "'" + effect + "' would no longer run");
                var range = argument.getRange().orElseThrow();
                for (var r : before.referencesFrom(call.site())) {
                    if (c.use(r.id()).getRange().filter(range::contains).isPresent()) gone.add(new Change.Dropped(r.site(), r.target()));
                }
            }
            without(edits, c.sources().unitOf(call.node()), call.arguments(), index);
        }
        names.remove(index);
        c.sources().apply(edits);
        c.ids().forget(param.id());
        Shared.resign(c, method, declaration.unit().path(), names);
        return gone;
    }

    /**
     * A parameter that becomes the object itself: its method's uses of it are {@code this}, and each call is made on
     * what it passed for it (a method reference cannot be), instead of what it was made on.
     */
    private static List<Change.Dropped> thisParam(Context c, Program before, Element param, ElementId into) {
        var method = before.element(param.parent());
        if ("true".equals(method.fact(ide.program.Fact.STATIC))) throw new PrimitiveRejected("delete.into", method.qualified() + " is static: it has no object");
        var declaration = c.declaration(method);
        CallableDeclaration<?> node = declaration.as(CallableDeclaration.class);
        var names = new ArrayList<>(Shared.parameterNames(node));
        var index = names.indexOf(param.name());
        var edits = new EditSet();
        var gone = new ArrayList<Change.Dropped>();
        for (var use : before.referencesTo(param.id())) {
            edits.replace(declaration.unit(), c.use(use.id()), "this");
            gone.add(new Change.Dropped(use.site(), into)); // merged, it was taken to bind the type: it binds nothing, it is this
        }
        var declared = node.getParameters().get(index).getRange().orElseThrow(); // the type it was declared with is named no more
        for (var r : before.referencesFrom(method.id())) {
            if (c.use(r.id()).getRange().filter(declared::contains).isPresent()) gone.add(new Change.Dropped(r.site(), r.target()));
        }
        without(edits, declaration.unit(), node.getParameters(), index);
        for (var call : Shared.callsTo(c, method.id())) {
            if (!(call.node() instanceof com.github.javaparser.ast.expr.MethodCallExpr made)) throw new PrimitiveRejected("delete.into", call.node() + " cannot be made on an object");
            var unit = c.sources().unitOf(made);
            var object = made.getArguments().get(index);
            var others = new ArrayList<String>();
            for (var i = 0; i < made.getArguments().size(); i++) if (i != index) others.add(unit.textOf(made.getArguments().get(i)));
            var receiver = object.isNameExpr() || object.isFieldAccessExpr() || object.isMethodCallExpr() || object.isObjectCreationExpr() || object.isThisExpr()
                    ? unit.textOf(object) : "(" + unit.textOf(object) + ")";
            edits.replace(unit, made, receiver + "." + made.getNameAsString() + "(" + String.join(", ", others) + ")");
            made.getScope().ifPresent(scope -> { // what it was made on is named no more there: a type, mostly
                for (var r : before.referencesFrom(call.site())) {
                    if (r.mode().isLink()) continue;
                    var use = c.use(r.id());
                    if (use == scope || scope.isAncestorOf(use)) gone.add(new Change.Dropped(r.site(), r.target()));
                }
            });
        }
        names.remove(index);
        c.sources().apply(edits);
        c.ids().forget(param.id());
        Shared.resign(c, method, declaration.unit().path(), names);
        return gone;
    }

    private static void without(EditSet edits, ide.java.text.Unit unit, com.github.javaparser.ast.NodeList<? extends com.github.javaparser.ast.Node> items, int index) {
        var texts = new ArrayList<>(Lists.texts(unit, items));
        texts.remove(index);
        Lists.rewrite(edits, unit, items, texts);
    }

}
