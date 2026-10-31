package ide.java.text;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.util.ArrayList;

/**
 * A source file: its path, its text, and the syntax tree parsed from that text. The text is the truth; the tree is
 * a reading of it, never edited. Offsets live only here: everyone else names places by nodes, positions and lines.
 */
public final class Unit {

    private final String path;
    private final String text;
    private final CompilationUnit tree;
    private int[] lineStarts;

    Unit(String path, String text, CompilationUnit tree) {
        this.path = path;
        this.text = text;
        this.tree = tree;
    }

    public String path() {
        return path;
    }

    public String text() {
        return text;
    }

    public CompilationUnit tree() {
        return tree;
    }

    /** The text of a node, as written. */
    public String textOf(Node node) {
        var range = node.getRange().orElseThrow();
        return text.substring(offset(range.begin), offset(range.end) + 1);
    }

    /** The text from {@code from} to {@code to}, both included. */
    public String textBetween(Position from, Position to) {
        return text.substring(offset(from), offset(to) + 1);
    }

    /** Line {@code line} (1-based) without its end of line; empty past the end. */
    public String line(int line) {
        if (line < 1 || line > lineStarts().length) return "";
        var start = lineStart(line);
        var end = text.indexOf('\n', start);
        return text.substring(start, end < 0 ? text.length() : end);
    }

    /** The blanks a line starts with. */
    public String indentation(int line) {
        var content = line(line);
        return content.substring(0, content.length() - content.stripLeading().length());
    }

    /** The position of the first {@code c} at or after {@code from}. */
    public Position next(char c, Position from) {
        var at = text.indexOf(c, offset(from));
        if (at < 0) throw new IllegalArgumentException("no '" + c + "' after " + from + " in " + path);
        return position(at);
    }

    /** The position of the first character after {@code after} that is not a blank or an end of line. */
    public Position nextToken(Position after) {
        var at = offset(after) + 1;
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        return position(at);
    }

    int offset(Position position) {
        return lineStart(position.line) + position.column - 1;
    }

    int lineStart(int line) {
        var starts = lineStarts();
        return line - 1 < starts.length ? starts[line - 1] : text.length();
    }

    Position position(int offset) {
        var starts = lineStarts();
        var line = 0;
        while (line + 1 < starts.length && starts[line + 1] <= offset) line++;
        return new Position(line + 1, offset - starts[line] + 1);
    }

    private int[] lineStarts() {
        if (lineStarts == null) {
            var starts = new ArrayList<Integer>();
            starts.add(0);
            for (var i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }
        return lineStarts;
    }
}
