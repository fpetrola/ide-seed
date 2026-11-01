package ide.projection;

import java.util.List;

/**
 * A compiler problem: stable {@code code}, how bad it is, where it is (line, and column and length from 1 on that
 * line, 0 if not known), the source text it is about ({@code symbol}), and what the compiler names in it
 * ({@code arguments}: the types of a mismatch, the method of a call), as it writes them.
 */
public record Diagnostic(String code, String path, int line, String symbol, String message, Severity severity, int column,
                         int length, List<String> arguments) {

    public Diagnostic {
        arguments = List.copyOf(arguments);
    }

    /** A problem the compiler names nothing in. */
    public Diagnostic(String code, String path, int line, String symbol, String message, Severity severity, int column, int length) {
        this(code, path, line, symbol, message, severity, column, length, List.of());
    }

    /** An error stops the code from running; a warning says something is likely wrong or useless. */
    public enum Severity { ERROR, WARNING }

    /** An error whose place on its line is not known. */
    public Diagnostic(String code, String path, int line, String symbol, String message) {
        this(code, path, line, symbol, message, Severity.ERROR, 0, 0);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
