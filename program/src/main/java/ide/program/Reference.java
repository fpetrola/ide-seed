package ide.program;

/** A use of, or a link to, {@code target}, written as {@code name}, from inside element {@code site}. */
public record Reference(String id, ElementId site, ElementId target, String name, Mode mode) {
}
