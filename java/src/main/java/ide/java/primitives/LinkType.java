package ide.java.primitives;

import ide.program.Change;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;

import java.util.List;

/** Makes a declaration name a workspace type: the extends clause, one more interface, or the declared type. */
final class LinkType {

    private LinkType() {
    }

    static Change apply(Primitive.Link link, Context c, Program before) {
        var type = before.element(link.target()).qualified();
        var replaced = usesInsideReplacedType(link, c, before); // read before editing: ids are the reading's
        var declaredTypeUse = localType(link, c, before);
        switch (link.mode()) {
            case EXTENDS -> SetAttribute.superclass(c, link.element(), type);
            case IMPLEMENTS -> SetAttribute.implement(c, link.element(), type);
            case DECLARED -> SetAttribute.type(c, link.element(), type);
            default -> throw new IllegalArgumentException("not a link: " + link.mode());
        }
        var change = link.intent(before, null);
        for (var use : replaced) change = change.expect(new Change.Dropped(use.site(), use.target()));
        if (declaredTypeUse != null) change = change.expect(new Change.Retargeted(declaredTypeUse.site(), declaredTypeUse.target(), link.target()));
        return change;
    }

    /**
     * The use a local's declared type is: a local's uses are its method's, so its type is not a link of its own, but a
     * use from the method, written in its declaration.
     */
    private static Reference localType(Primitive.Link link, Context c, Program before) {
        var local = before.element(link.element());
        if (link.mode() != Mode.DECLARED || local.kind() != ide.program.Kind.LOCAL) return null;
        var declared = switch (c.declaration(local).dom()) {
            case org.eclipse.jdt.core.dom.VariableDeclarationFragment fragment -> switch (fragment.getParent()) {
                case org.eclipse.jdt.core.dom.VariableDeclarationStatement statement -> statement.getType();
                case org.eclipse.jdt.core.dom.VariableDeclarationExpression expression -> expression.getType();
                default -> throw new IllegalStateException("not a local: " + fragment);
            };
            case org.eclipse.jdt.core.dom.SimpleName loop -> ((org.eclipse.jdt.core.dom.SingleVariableDeclaration) loop.getParent()).getType();
            default -> throw new IllegalStateException("not a local: " + local);
        };
        return before.referencesFrom(local.parent()).stream().filter(r -> r.mode().isTypeUse())
                .filter(r -> holds(declared, c.useDom(r.id()))).findFirst().orElse(null);
    }

    /** The uses written inside the type a link replaces, such as its type arguments: they go with it. */
    private static List<Reference> usesInsideReplacedType(Primitive.Link link, Context c, Program before) {
        if (link.mode() == Mode.IMPLEMENTS) return List.of(); // implements adds a type, it replaces none
        var old = before.link(link.element(), link.mode());
        if (old.isEmpty()) return List.of();
        var type = c.useDom(old.get().id());
        return before.referencesFrom(link.element()).stream()
                .filter(use -> !use.equals(old.get()) && holds(type, c.useDom(use.id())) && !holds(c.useDom(use.id()), type)).toList();
    }

    /** Whether one node of a text is within another, as where they are written says. */
    private static boolean holds(org.eclipse.jdt.core.dom.ASTNode outer, org.eclipse.jdt.core.dom.ASTNode inner) {
        return outer.getStartPosition() <= inner.getStartPosition()
                && inner.getStartPosition() + inner.getLength() <= outer.getStartPosition() + outer.getLength();
    }
}
