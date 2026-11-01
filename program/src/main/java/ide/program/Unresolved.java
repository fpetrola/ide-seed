package ide.program;

/**
 * A use whose name binds nothing: written as {@code name}, from inside {@code site}. Its id is the site and its place
 * among the site's uses, like a reference's, so that a retarget can name it to bind it. A use of a member is looked up
 * in a type, its {@code scope}: the receiver's, or the one it is written in; null for a type, or a type not known. A
 * use made in a body says which of its statements it is in, as a reference does.
 */
public record Unresolved(String id, ElementId site, String name, Mode mode, ElementId scope, ElementId statement) {

    /** A use that names no member, or one whose type is not known. */
    public Unresolved(String id, ElementId site, String name, Mode mode) {
        this(id, site, name, mode, null);
    }

    public Unresolved(String id, ElementId site, String name, Mode mode, ElementId scope) {
        this(id, site, name, mode, scope, null);
    }
}
