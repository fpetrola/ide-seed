package ide.session;

import ide.core.TextDiff;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A file before and after, as two columns of rows side by side. A row is what stayed (same on both sides), what went
 * (removed, only on the left), what came (added, only on the right), or a line changed (changed on both: a run of
 * lines that went, next to a run that came, row by row). Lines that went from one place and came to another, maybe
 * indented otherwise, are moved on both sides, not removed and added. A row whose left line is one of {@code zone},
 * the lines a step takes, is in the zone; each run of changes with no line in it is also changed, at its first line.
 */
record SideBySide(List<Row> rows, List<Also> also) {

    /** {@code leftKind}: same, removed, changed, moved or none; {@code rightKind}: same, added, changed, moved or none. */
    record Row(int leftLine, String left, String leftKind, int rightLine, String right, String rightKind, boolean zone) {
        Map<String, Object> shown() {
            var shown = new LinkedHashMap<String, Object>();
            shown.put("leftLine", leftLine);
            shown.put("left", left);
            shown.put("leftKind", leftKind);
            shown.put("rightLine", rightLine);
            shown.put("right", right);
            shown.put("rightKind", rightKind);
            shown.put("zone", zone);
            return shown;
        }
    }

    /** A change outside the zone: the row it starts at, its line (after, else before) and that line's text. */
    record Also(int row, int line, String text) {
    }

    /** {@code before} null: the file is new; {@code after} null: it is gone. */
    static SideBySide of(String before, String after, Set<Integer> zone) {
        var left = before == null ? List.<String>of() : before.lines().toList();
        var right = after == null ? List.<String>of() : after.lines().toList();
        var places = TextDiff.common(left, right);
        // the lines in order: kept pairs, and what went or came between them
        record Op(int left, int right) { // -1 on the side it is not
        }
        var ops = new ArrayList<Op>();
        var i = 0;
        for (var j = 0; j < right.size(); j++) {
            if (places[j] < 0) continue;
            while (i < places[j]) ops.add(new Op(i++, -1));
            ops.add(new Op(i++, j));
        }
        while (i < left.size()) ops.add(new Op(i++, -1));
        // what came, placed before the next kept line it is before
        var ordered = new ArrayList<Op>();
        var nextRight = 0;
        for (var op : ops) {
            if (op.right() >= 0) {
                while (nextRight < op.right()) ordered.add(new Op(-1, nextRight++));
                nextRight = op.right() + 1;
            }
            ordered.add(op);
        }
        while (nextRight < right.size()) ordered.add(new Op(-1, nextRight++));
        // moved: what went and what came, alike but for indentation, one for one
        var went = new HashMap<String, List<Integer>>();
        for (var op : ordered) {
            if (op.right() < 0 && meaningful(left.get(op.left()))) went.computeIfAbsent(left.get(op.left()).strip(), key -> new ArrayList<>()).add(op.left());
        }
        var movedLeft = new java.util.HashSet<Integer>();
        var movedRight = new java.util.HashSet<Integer>();
        for (var op : ordered) {
            if (op.left() >= 0 || !meaningful(right.get(op.right()))) continue;
            var from = went.get(right.get(op.right()).strip());
            if (from != null && !from.isEmpty()) {
                movedLeft.add(from.removeFirst());
                movedRight.add(op.right());
            }
        }
        // rows: a run of what went next to a run of what came pairs up as changed, row by row
        var rows = new ArrayList<Row>();
        for (var at = 0; at < ordered.size(); ) {
            var op = ordered.get(at);
            if (op.left() >= 0 && op.right() >= 0) {
                rows.add(row(left, right, op.left(), op.right(), "same", "same", zone));
                at++;
                continue;
            }
            var gone = new ArrayList<Integer>();
            var come = new ArrayList<Integer>();
            while (at < ordered.size() && (ordered.get(at).left() < 0 || ordered.get(at).right() < 0)) {
                var change = ordered.get(at++);
                if (change.left() >= 0) gone.add(change.left());
                else come.add(change.right());
            }
            var pairedGone = gone.stream().filter(l -> !movedLeft.contains(l)).toList();
            var pairedCome = come.stream().filter(r -> !movedRight.contains(r)).toList();
            var pairs = Math.min(pairedGone.size(), pairedCome.size());
            for (var l : gone) {
                if (movedLeft.contains(l)) rows.add(row(left, right, l, -1, "moved", "none", zone));
                else if (pairedGone.indexOf(l) < pairs) rows.add(row(left, right, l, pairedCome.get(pairedGone.indexOf(l)), "changed", "changed", zone));
                else rows.add(row(left, right, l, -1, "removed", "none", zone));
            }
            for (var r : come) {
                if (movedRight.contains(r)) rows.add(row(left, right, -1, r, "none", "moved", zone));
                else if (pairedCome.indexOf(r) >= pairs) rows.add(row(left, right, -1, r, "none", "added", zone));
            }
        }
        // also changed: each run of rows that are not the same, none of them in the zone
        var also = new ArrayList<Also>();
        for (var at = 0; at < rows.size(); ) {
            if (rows.get(at).leftKind().equals("same")) {
                at++;
                continue;
            }
            var start = at;
            var inZone = false;
            while (at < rows.size() && !rows.get(at).leftKind().equals("same")) inZone |= rows.get(at++).zone();
            if (!inZone && !zone.isEmpty()) {
                var first = rows.get(start);
                also.add(first.rightLine() > 0 ? new Also(start, first.rightLine(), first.right()) : new Also(start, first.leftLine(), first.left()));
            }
        }
        return new SideBySide(List.copyOf(rows), List.copyOf(also));
    }

    private static Row row(List<String> left, List<String> right, int l, int r, String leftKind, String rightKind, Set<Integer> zone) {
        return new Row(l < 0 ? 0 : l + 1, l < 0 ? "" : left.get(l), leftKind, r < 0 ? 0 : r + 1, r < 0 ? "" : right.get(r), rightKind,
                l >= 0 && zone.contains(l + 1));
    }

    /** A line that says something: not blank, not a lone brace. */
    private static boolean meaningful(String line) {
        var text = line.strip();
        return !text.isEmpty() && !Set.of("{", "}", "};", ")", ");").contains(text);
    }
}
