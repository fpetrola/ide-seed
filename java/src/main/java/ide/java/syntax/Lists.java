package ide.java.syntax;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import ide.java.text.EditSet;
import ide.java.text.Unit;

import java.util.List;

/** Parameters and arguments: parenthesized lists of texts separated by commas. */
public final class Lists {

    private Lists() {
    }

    /** One more item at the end of the list whose '(' follows {@code name}. */
    public static void append(EditSet edits, Unit unit, NodeList<? extends Node> items, Node name, String item) {
        append(edits, unit, items, name.getEnd().orElseThrow(), item);
    }

    /** The same, the list being in the first parentheses after {@code from}. */
    public static void append(EditSet edits, Unit unit, NodeList<? extends Node> items, com.github.javaparser.Position from, String item) {
        if (items.isNonEmpty()) {
            edits.insertAfter(unit, items.getLast().orElseThrow(), ", " + item);
        } else {
            var open = unit.next('(', from);
            edits.insertAt(unit, unit.next(')', open), item);
        }
    }

    /** The list written anew from {@code texts} (fewer than the items drops some; none empties the list). */
    public static void rewrite(EditSet edits, Unit unit, NodeList<? extends Node> items, List<String> texts) {
        edits.replace(unit, items.getFirst().orElseThrow().getBegin().orElseThrow(),
                items.getLast().orElseThrow().getEnd().orElseThrow(), String.join(", ", texts));
    }

    /** The text of each item, as written. */
    public static List<String> texts(Unit unit, NodeList<? extends Node> items) {
        return items.stream().map(item -> unit.textOf(item)).toList();
    }
}
