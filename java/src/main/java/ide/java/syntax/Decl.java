package ide.java.syntax;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import ide.java.text.Located;
import ide.java.text.Unit;

/**
 * Where an element is declared: its node (a type, a field declaration, a constructor, a method, a parameter) and the
 * unit whose text it was read from. It keeps where the node is, not the node, so it keeps no tree.
 */
public final class Decl {

    private final Located at;

    public Decl(Unit unit, Node node) {
        this.at = new Located(unit, node);
    }

    public Unit unit() {
        return at.unit();
    }

    /** Where the declaration is written, without finding its node. */
    public Range range() {
        return at.range();
    }

    public Node node() {
        return at.node();
    }

    /** The node, as the kind of declaration it is known to be. */
    public <T extends Node> T as(Class<T> type) {
        return type.cast(node());
    }
}
