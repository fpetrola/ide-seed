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
        switch (link.mode()) {
            case EXTENDS -> SetAttribute.superclass(c, link.element(), type);
            case IMPLEMENTS -> SetAttribute.implement(c, link.element(), type);
            case DECLARED -> SetAttribute.type(c, link.element(), type);
            default -> throw new IllegalArgumentException("not a link: " + link.mode());
        }
        var change = link.intent(before, null);
        for (var use : replaced) change = change.expect(new Change.Dropped(use.site(), use.target()));
        return change;
    }

    /** The uses written inside the type a link replaces, such as its type arguments: they go with it. */
    private static List<Reference> usesInsideReplacedType(Primitive.Link link, Context c, Program before) {
        if (link.mode() == Mode.IMPLEMENTS) return List.of(); // implements adds a type, it replaces none
        var old = before.link(link.element(), link.mode());
        if (old.isEmpty()) return List.of();
        var type = c.use(old.get().id());
        return before.referencesFrom(link.element()).stream()
                .filter(use -> !use.equals(old.get()) && type.isAncestorOf(c.use(use.id()))).toList();
    }
}
