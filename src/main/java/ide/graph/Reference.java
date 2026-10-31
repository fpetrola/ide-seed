package ide.graph;

/** A use of {@code target}, written as {@code name}, from inside element {@code site}. */
public record Reference(ElementId site, ElementId target, String name) {
}
