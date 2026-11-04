package ide.java.text;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;

/**
 * Where a node of a unit is: its range and its kind. It keeps no tree; the node is found in whatever tree the unit has
 * when it is asked for, so a reading can last without keeping every file's tree.
 */
public final class Located {

    private final Unit unit;
    private final Range range;
    private final Class<? extends Node> kind;

    public Located(Unit unit, Node node) {
        this.unit = unit;
        this.range = node.getRange().orElseThrow();
        this.kind = node.getClass();
    }

    public Unit unit() {
        return unit;
    }

    public Range range() {
        return range;
    }

    /** The node, in the unit's tree as it is now. */
    public Node node() {
        return unit.find(range, kind);
    }
}
