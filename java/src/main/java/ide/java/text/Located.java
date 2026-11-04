package ide.java.text;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

/**
 * Where a node of a unit is: its range and its kind. It keeps no tree; the node is found in whatever tree the unit has
 * when it is asked for, so a reading can last without keeping every file's tree.
 */
public final class Located {

    private final Unit unit;
    private final int beginLine, beginColumn, endLine, endColumn; // a program has millions: no Range of two Positions each
    private final Class<? extends Node> kind;

    public Located(Unit unit, Node node) {
        var range = node.getRange().orElseThrow();
        this.unit = unit;
        this.beginLine = range.begin.line;
        this.beginColumn = range.begin.column;
        this.endLine = range.end.line;
        this.endColumn = range.end.column;
        this.kind = node.getClass();
    }

    /** A node of a kind (or of a kind's kinds) from its first character to its last, as offsets of the unit's text. */
    public Located(Unit unit, int start, int end, Class<? extends Node> kind) {
        var begin = unit.position(start);
        var last = unit.position(end);
        this.unit = unit;
        this.beginLine = begin.line;
        this.beginColumn = begin.column;
        this.endLine = last.line;
        this.endColumn = last.column;
        this.kind = kind;
    }

    public Unit unit() {
        return unit;
    }

    public Range range() {
        return Range.range(beginLine, beginColumn, endLine, endColumn);
    }

    /** Whether the range holds a place, without making it. */
    public boolean contains(Position position) {
        return (position.line > beginLine || position.line == beginLine && position.column >= beginColumn)
                && (position.line < endLine || position.line == endLine && position.column <= endColumn);
    }

    /** The node, in the unit's tree as it is now. */
    public Node node() {
        return unit.find(range(), kind);
    }
}
