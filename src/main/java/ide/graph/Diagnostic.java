package ide.graph;

/** A compiler problem: stable {@code code}, where it is, and the source text it is about ({@code symbol}). */
public record Diagnostic(String code, String path, int line, String symbol, String message) {
}
