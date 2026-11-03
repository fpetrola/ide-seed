package ide.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files of a step side by side, as the IDE aligned them (replay-step's rows): before on the left, after on the
 * right, row by row, highlighted as Java. Under the highlighting, what went is red, what came green, a changed line
 * amber on both sides, moved lines blue on both, and the lines the step takes yellow where they stayed, marked ▌ in
 * the margin. A new file has nothing on the left.
 */
final class SideBySidePane extends JPanel {

    private static final Color BACKGROUND = new Color(0x2B2B2B);

    private final Map<String, RSyntaxTextArea> lefts = new LinkedHashMap<>();
    private final List<String> lines = new ArrayList<>();

    SideBySidePane() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(BACKGROUND);
    }

    @SuppressWarnings("unchecked")
    void show(List<Map<String, Object>> changes) {
        removeAll();
        lefts.clear();
        lines.clear();
        for (var change : changes) {
            var path = String.valueOf(change.get("path"));
            var status = String.valueOf(change.get("status"));
            var header = new JLabel(path + (status.equals("changed") ? "" : "  (" + status + ")"));
            header.setForeground(new Color(0xCCCCCC));
            header.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(header);
            var rows = (List<Map<String, Object>>) change.get("rows");
            var leftNumbers = new StringBuilder();
            var leftText = new StringBuilder();
            var rightNumbers = new StringBuilder();
            var rightText = new StringBuilder();
            for (var row : rows) {
                var zone = Boolean.TRUE.equals(row.get("zone"));
                leftNumbers.append(zone ? "▌" : " ").append(number(row.get("leftLine"))).append('\n');
                leftText.append(row.get("left")).append('\n');
                rightNumbers.append(' ').append(number(row.get("rightLine"))).append('\n');
                rightText.append(row.get("right")).append('\n');
                lines.add(path + " " + row.get("leftKind") + (zone ? "*" : "") + ":" + row.get("left") + " | " + row.get("rightKind") + ":" + row.get("right"));
            }
            var left = Code.area();
            left.setText(leftText.toString());
            var right = Code.area();
            right.setText(rightText.toString());
            for (var i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                mark(left, i, String.valueOf(row.get("leftKind")), Boolean.TRUE.equals(row.get("zone")));
                mark(right, i, String.valueOf(row.get("rightKind")), false);
            }
            var columns = new JPanel(new GridBagLayout());
            columns.setBackground(BACKGROUND);
            columns.add(numbers(leftNumbers.toString()), constraints(0, 0));
            columns.add(left, constraints(1, 1));
            columns.add(numbers(rightNumbers.toString()), constraints(2, 0));
            columns.add(right, constraints(3, 1));
            columns.setAlignmentX(Component.LEFT_ALIGNMENT);
            columns.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, columns.getPreferredSize().height));
            add(columns);
            lefts.put(path, left);
        }
        add(javax.swing.Box.createVerticalGlue()); // the files from the top, the rest empty
        revalidate();
        repaint();
    }

    /** Scrolls to a row of a file. */
    void reveal(String path, int row) {
        var area = lefts.get(path);
        if (area == null) return;
        try {
            var at = area.modelToView2D(area.getLineStartOffset(Math.max(0, Math.min(row, area.getLineCount() - 1))));
            if (at != null) area.scrollRectToVisible(new Rectangle(0, (int) at.getY(), 1, (int) at.getHeight() * 8));
        } catch (javax.swing.text.BadLocationException | RuntimeException nowhere) {
            // a row that is not there: stay
        }
    }

    private static void mark(RSyntaxTextArea area, int row, String kind, boolean zone) {
        var color = switch (kind) {
            case "removed" -> Code.REMOVED;
            case "added" -> Code.ADDED;
            case "changed" -> Code.CHANGED;
            case "moved" -> Code.MOVED;
            case "none" -> Code.NONE;
            default -> zone ? Code.ZONE : null;
        };
        if (color != null) Code.mark(area, row, color);
    }

    /** A column of line numbers, the same height a line as the code beside it. */
    private static RSyntaxTextArea numbers(String text) {
        var numbers = Code.area();
        numbers.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        numbers.setForeground(new Color(0x606366));
        numbers.setText(text);
        return numbers;
    }

    private static GridBagConstraints constraints(int x, double weight) {
        var constraints = new GridBagConstraints();
        constraints.gridx = x;
        constraints.gridy = 0;
        constraints.weightx = weight;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        return constraints;
    }

    private static String number(Object line) {
        var n = line instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(line));
        return n == 0 ? "    " : String.format("%4d", n);
    }

    /** Each row as {@code path leftKind[*]:left | rightKind:right}, * in the zone: what the tests read. */
    List<String> lines() {
        return List.copyOf(lines);
    }
}
