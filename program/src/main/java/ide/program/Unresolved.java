package ide.program;

import java.util.List;

/**
 * A use whose name binds nothing: written as {@code name}, from inside {@code site}. Its id is the site and its place
 * among the site's uses, like a reference's, so that a retarget can name it to bind it. A use of a member is looked up
 * in a type, its {@code scope}: the receiver's, or the one it is written in; null for a type, or a type not known. It
 * also says what it is known to need, as its language writes types: the type its value must have where it is written
 * ({@code expected}; for a call whose value nothing takes, the language's "nothing"), and, for a call, what it passes.
 */
public record Unresolved(String id, ElementId site, String name, Mode mode, ElementId scope, String expected,
                         List<Argument> arguments) {

    /** What a call passes: a name for it, as the language would name a parameter that takes it, and its type. */
    public record Argument(String name, String type) {
    }

    public Unresolved {
        arguments = List.copyOf(arguments);
    }

    /** A use that names no member, or one whose type is not known, of which nothing more is known. */
    public Unresolved(String id, ElementId site, String name, Mode mode) {
        this(id, site, name, mode, null, null, List.of());
    }
}
