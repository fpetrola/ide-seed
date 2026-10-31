package ide.graph;

/**
 * A use of {@code target}, written as {@code name}, from inside element {@code site}.
 * {@code mode} is read, write, update (compound assignment, ++/--) or call.
 */
public record Reference(String id, ElementId site, ElementId target, String name, String mode) {
}
