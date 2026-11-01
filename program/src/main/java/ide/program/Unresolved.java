package ide.program;

/**
 * A use whose name binds nothing: written as {@code name}, from inside {@code site}. Its id is the site and its place
 * among the site's uses, like a reference's, so that a retarget can name it to bind it.
 */
public record Unresolved(String id, ElementId site, String name, Mode mode) {
}
