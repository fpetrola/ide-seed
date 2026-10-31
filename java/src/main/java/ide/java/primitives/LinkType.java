package ide.java.primitives;

import ide.program.Change;
import ide.program.Primitive;
import ide.program.Program;

/** Makes a declaration name a workspace type: the extends clause, one more interface, or the declared type. */
final class LinkType {

    private LinkType() {
    }

    static Change apply(Primitive.Link link, Context c, Program before) {
        var type = before.element(link.target()).qualified();
        switch (link.mode()) {
            case EXTENDS -> SetAttribute.superclass(c, link.element(), type);
            case IMPLEMENTS -> SetAttribute.implement(c, link.element(), type);
            case DECLARED -> SetAttribute.type(c, link.element(), type);
            default -> throw new IllegalArgumentException("not a link: " + link.mode());
        }
        return link.intent(before, null);
    }
}
