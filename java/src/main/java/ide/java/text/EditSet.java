package ide.java.text;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits of the text of one or more units, made together. Every place refers to the text of the units as they were
 * when the edits were made; {@link Sources#apply} applies each unit's edits back to front, so none moves another.
 * Insertions at one place keep the order they were made in.
 */
public final class EditSet {

    private record Edit(Unit unit, int start, int end, String text, int order) {
    }

    private final List<Edit> edits = new ArrayList<>();

    public EditSet replace(Unit unit, Node node, String text) {
        var range = node.getRange().orElseThrow();
        return replace(unit, range.begin, range.end, text);
    }

    /** Replaces the text from {@code from} to {@code to}, both included. */
    public EditSet replace(Unit unit, Position from, Position to, String text) {
        return add(unit, unit.offset(from), unit.offset(to) + 1, text);
    }

    public EditSet insertBefore(Unit unit, Node node, String text) {
        return insertAt(unit, node.getBegin().orElseThrow(), text);
    }

    public EditSet insertAfter(Unit unit, Node node, String text) {
        var at = unit.offset(node.getEnd().orElseThrow()) + 1;
        return add(unit, at, at, text);
    }

    /** Inserts before the character at {@code position}. */
    public EditSet insertAt(Unit unit, Position position, String text) {
        var at = unit.offset(position);
        return add(unit, at, at, text);
    }

    /** Inserts at the start of line {@code line} (1-based; one past the last line is the end of the text). */
    public EditSet insertAtLine(Unit unit, int line, String text) {
        var at = unit.lineStart(line);
        return add(unit, at, at, text);
    }

    /** Removes a node and the blanks that follow it on its line, as a modifier goes. */
    public EditSet removeWithSpaceAfter(Unit unit, Node node) {
        var range = node.getRange().orElseThrow();
        var start = unit.offset(range.begin);
        var end = unit.offset(range.end) + 1;
        var text = unit.text();
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        return add(unit, start, end, "");
    }

    /**
     * Removes whole lines {@code first..last}. A blank line they would leave next to another blank line, or between a
     * blank line and a closing brace, goes with them; so does a blank line they would leave right after an opening one.
     */
    public EditSet removeLines(Unit unit, int first, int last) {
        var before = first > 1 ? unit.line(first - 1).strip() : null;
        var after = unit.line(last + 1).strip();
        if (before != null && before.isEmpty() && (after.isEmpty() || after.startsWith("}"))) first--;
        else if (after.isEmpty() && before != null && before.endsWith("{")) last++;
        return add(unit, unit.lineStart(first), unit.lineStart(last + 1), "");
    }

    public boolean isEmpty() {
        return edits.isEmpty();
    }

    private EditSet add(Unit unit, int start, int end, String text) {
        edits.add(new Edit(unit, start, end, text, edits.size()));
        return this;
    }

    /** The new text of every unit touched: each unit's edits, back to front. */
    Map<String, String> texts() {
        var byUnit = new LinkedHashMap<Unit, List<Edit>>();
        for (var edit : edits) byUnit.computeIfAbsent(edit.unit(), u -> new ArrayList<>()).add(edit);
        var texts = new LinkedHashMap<String, String>();
        byUnit.forEach((unit, list) -> {
            var sorted = list.stream().distinct().sorted(Comparator.comparingInt(Edit::start).reversed()
                    .thenComparing(Comparator.comparingInt((Edit e) -> e.end() - e.start()).reversed())
                    .thenComparing(Comparator.comparingInt(Edit::order).reversed())).toList();
            var text = new StringBuilder(unit.text());
            Edit previous = null;
            for (var edit : sorted) {
                if (previous != null && edit.end() > previous.start()) {
                    throw new IllegalStateException("overlapping edits in " + unit.path() + ": " + edit + " and " + previous);
                }
                text.replace(edit.start(), edit.end(), edit.text());
                previous = edit;
            }
            texts.put(unit.path(), text.toString());
        });
        return texts;
    }
}
