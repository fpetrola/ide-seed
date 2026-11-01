package ide.projection;

/** A place in a projection's text: a file, and a line and a column in it, both from 1. */
public record Location(String path, int line, int column) {
}
