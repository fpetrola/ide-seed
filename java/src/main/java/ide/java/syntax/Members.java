package ide.java.syntax;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import ide.java.text.EditSet;
import ide.java.text.Unit;

import java.util.stream.Collectors;

/** The members of a type as whole lines: where one goes, how one leaves, and its text to write elsewhere. */
public final class Members {

    private Members() {
    }

    /** How the members of a type are indented: one level deeper than the type. */
    public static String indentation(Unit unit, TypeDeclaration<?> type) {
        return unit.indentation(type.getBegin().orElseThrow().line) + "    ";
    }

    /** A member at the end of the type, after a blank line. */
    public static void append(EditSet edits, Unit unit, TypeDeclaration<?> type, String member) {
        var end = type.getEnd().orElseThrow();
        var text = "\n" + indentation(unit, type) + member + "\n";
        if (unit.line(end.line).strip().equals("}")) edits.insertAtLine(unit, end.line, text);
        else edits.insertAt(unit, end, text + unit.indentation(type.getBegin().orElseThrow().line));
    }

    /** A field after the type's last field, or first in its body, followed by a blank line if other members follow. */
    public static void appendField(EditSet edits, Unit unit, TypeDeclaration<?> type, String field) {
        var line = indentation(unit, type) + field + "\n";
        var fields = type.getFields();
        if (!fields.isEmpty()) {
            edits.insertAtLine(unit, fields.getLast().getEnd().orElseThrow().line + 1, line);
        } else {
            var open = unit.next('{', type.getName().getEnd().orElseThrow());
            edits.insertAtLine(unit, open.line + 1, line + (type.getMembers().isEmpty() ? "" : "\n"));
        }
    }

    /** A member just above {@code anchor} (above its comment, if it has one), then a blank line. */
    public static void insertBefore(EditSet edits, Unit unit, Node anchor, String member) {
        var line = firstLine(anchor);
        edits.insertAtLine(unit, line, unit.indentation(line) + member + "\n\n");
    }

    /** A member's lines, its comment included. */
    public static void remove(EditSet edits, Unit unit, Node member) {
        edits.removeLines(unit, firstLine(member), member.getEnd().orElseThrow().line);
    }

    /**
     * A member's text, its comment included, to be written at {@code indent}: the first line without indentation, the
     * others moved from the member's indentation to {@code indent}.
     */
    public static String text(Unit unit, Node member, String indent) {
        return text(unit, member, indent, java.util.Map.of());
    }

    /** The same, with nodes inside it written as {@code instead} says. */
    public static String text(Unit unit, Node member, String indent, java.util.Map<Node, String> instead) {
        var first = firstLine(member);
        var own = unit.indentation(first);
        var start = new Position(first, own.length() + 1);
        var written = unit.textBetween(start, member.getEnd().orElseThrow());
        var last = new java.util.ArrayList<>(instead.keySet());
        last.sort(java.util.Comparator.comparing((Node n) -> n.getBegin().orElseThrow()).reversed()); // from the end, so places hold
        for (var node : last) {
            var from = unit.textBetween(start, node.getBegin().orElseThrow()).length() - 1;
            var to = unit.textBetween(start, node.getEnd().orElseThrow()).length();
            written = written.substring(0, from) + instead.get(node) + written.substring(to);
        }
        var lines = written.lines().toList();
        return lines.getFirst() + lines.stream().skip(1)
                .map(line -> "\n" + (line.startsWith(own) ? indent + line.substring(own.length()) : line))
                .collect(Collectors.joining());
    }

    /** The first line of a member: its comment's, if it has one. */
    private static int firstLine(Node member) {
        return member.getComment().flatMap(Node::getBegin).orElse(member.getBegin().orElseThrow()).line;
    }
}
