package ide.java.syntax;

import com.github.javaparser.ast.Node;
import ide.java.text.Unit;

/**
 * Where an element is declared: its node (a type, a field declaration, a constructor, a method, a parameter) and the
 * unit whose text it was read from.
 */
public record Decl(Unit unit, Node node) {

    /** The node, as the kind of declaration it is known to be. */
    public <T extends Node> T as(Class<T> type) {
        return type.cast(node);
    }
}
