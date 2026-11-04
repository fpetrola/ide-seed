package ide.java.text;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Node;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Optional;

/**
 * A source file: its path, its text, and the syntax tree parsed from that text. The text is the truth; the tree is
 * a reading of it, never edited, let go when nothing uses it and parsed again when asked for. Offsets live only here:
 * everyone else names places by nodes, positions and lines.
 */
public final class Unit {

    private final String path;
    private final String text;
    private final Trees trees;
    private WeakReference<CompilationUnit> tree;
    private int[] lineStarts;

    private static final DataKey<Unit> UNIT = new DataKey<>() {
    };

    Unit(String path, String text, CompilationUnit tree, Trees trees) {
        this.path = path;
        this.text = text;
        this.trees = trees;
        hold(tree);
    }

    /** The unit a tree was read for, if any. */
    static Unit of(CompilationUnit tree) {
        return tree.containsData(UNIT) ? tree.getData(UNIT) : null;
    }

    public String path() {
        return path;
    }

    public String text() {
        return text;
    }

    /** The tree, the same one while anything holds a node of it, else read again from the text. */
    public CompilationUnit tree() {
        var held = tree.get();
        if (held == null) held = hold(trees.again(this));
        trees.used(this, held);
        return held;
    }

    /** A tree parsed ahead of being asked for: taken unless the unit has one in memory (that one stays). */
    void adopt(CompilationUnit parsed) {
        if (tree.get() == null) hold(parsed);
    }

    /** The tree if it is in memory: what was cached in it is there only. */
    public Optional<CompilationUnit> parsed() {
        return Optional.ofNullable(tree.get());
    }

    private CompilationUnit hold(CompilationUnit parsed) {
        parsed.setData(UNIT, this);
        tree = new WeakReference<>(parsed);
        trees.used(this, parsed);
        return parsed;
    }

    /**
     * The outermost node of a kind with exactly this range: what a {@link Located} names. Looked for down the nodes whose
     * range holds it; a node may lie outside its parent's (the type of a variable is its declarator's child), so failing
     * that, in the whole tree.
     */
    Node find(Range range, Class<? extends Node> kind) {
        var found = find(tree(), range, kind);
        if (found != null) return found;
        return tree().findFirst(Node.class, node -> node.getClass() == kind && node.getRange().filter(range::equals).isPresent())
                .orElseThrow(() -> new IllegalStateException("no " + kind.getSimpleName() + " at " + range + " in " + path));
    }

    private static Node find(Node at, Range range, Class<? extends Node> kind) {
        if (at.getClass() == kind && at.getRange().filter(range::equals).isPresent()) return at;
        for (var child : at.getChildNodes()) {
            if (child.getRange().filter(r -> r.contains(range)).isEmpty()) continue;
            var found = find(child, range, kind);
            if (found != null) return found;
        }
        return null;
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

    /** Whole lines {@code first..last}, with their ends of line. */
    public String lines(int first, int last) {
        return text.substring(lineStart(first), lineStart(last + 1));
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

    /** The position of the last character before {@code before} that is not a blank or an end of line. */
    public Position previousToken(Position before) {
        var at = offset(before) - 1;
        while (at > 0 && Character.isWhitespace(text.charAt(at))) at--;
        return position(at);
    }

    /** The position right after {@code position}, on the same line. */
    public static Position after(Position position) {
        return new Position(position.line, position.column + 1);
    }

    /** Where a position is in the text, from 0: as a compiler counts characters. */
    public int offsetOf(Position position) {
        return offset(position);
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
