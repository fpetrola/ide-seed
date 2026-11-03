package ide.ui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The files of a step side by side, as the IDE aligned them (replay-step's rows): before on the left, after on the
 * right, row by row. What went is red, what came green, a changed line both, moved lines blue on both sides, and the
 * lines the step takes marked in the left margin and in yellow where they stayed. A new file has nothing on the left.
 */
final class SideBySidePane extends JPanel {

    private static final Color TEXT = new Color(0xA9B7C6);
    private static final Color BACKGROUND = new Color(0x2B2B2B);

    private final Map<String, JTextPane> lefts = new LinkedHashMap<>();
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
            var left = pane();
            var right = pane();
            for (var row : (List<Map<String, Object>>) change.get("rows")) {
                var zone = Boolean.TRUE.equals(row.get("zone"));
                var leftKind = String.valueOf(row.get("leftKind"));
                var rightKind = String.valueOf(row.get("rightKind"));
                line(left, (zone ? "▌" : " ") + number(row.get("leftLine")), String.valueOf(row.get("left")), leftKind, zone);
                line(right, " " + number(row.get("rightLine")), String.valueOf(row.get("right")), rightKind, false);
                lines.add(path + " " + leftKind + (zone ? "*" : "") + ":" + row.get("left") + " | " + rightKind + ":" + row.get("right"));
            }
            var columns = new JPanel(new GridLayout(1, 2, 2, 0));
            columns.setBackground(BACKGROUND);
            columns.add(left);
            columns.add(right);
            columns.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(columns);
            lefts.put(path, left);
        }
        revalidate();
        repaint();
    }

    /** Scrolls to a row of a file. */
    void reveal(String path, int row) {
        var pane = lefts.get(path);
        if (pane == null) return;
        try {
            var offset = pane.getDocument().getDefaultRootElement().getElement(Math.max(0, row)).getStartOffset();
            var at = pane.modelToView2D(offset);
            if (at != null) pane.scrollRectToVisible(new Rectangle(0, (int) at.getY(), 1, (int) at.getHeight() * 8));
        } catch (BadLocationException | RuntimeException nowhere) {
            // a row that is not there: stay
        }
    }

    private static JTextPane pane() {
        var pane = new JTextPane();
        pane.setEditable(false);
        pane.setFont(Zoom.code(13));
        pane.setBackground(BACKGROUND);
        return pane;
    }

    private static String number(Object line) {
        var n = line instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(line));
        return n == 0 ? "     " : String.format("%4d ", n);
    }

    private static void line(JTextPane pane, String margin, String text, String kind, boolean zone) {
        var document = pane.getStyledDocument();
        var marginStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(marginStyle, zone ? new Color(0xE0C46C) : new Color(0x606366));
        var style = new SimpleAttributeSet();
        StyleConstants.setForeground(style, TEXT);
        switch (kind) {
            case "removed" -> background(style, new Color(0x4B2A2A), new Color(0xE5A3A3));
            case "added" -> background(style, new Color(0x294436), new Color(0x9BCB8A));
            case "changed" -> background(style, new Color(0x3F3A2A), new Color(0xE0D2A0));
            case "moved" -> background(style, new Color(0x2F3B5C), new Color(0xA8C0F0));
            case "none" -> StyleConstants.setBackground(style, new Color(0x313335));
            default -> {
                if (zone) StyleConstants.setBackground(style, new Color(0x52503A));
            }
        }
        try {
            document.insertString(document.getLength(), margin, marginStyle);
            document.insertString(document.getLength(), text + (document.getLength() >= 0 ? "\n" : ""), style);
        } catch (BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void background(SimpleAttributeSet style, Color background, Color foreground) {
        StyleConstants.setBackground(style, background);
        StyleConstants.setForeground(style, foreground);
    }

    /** Each row as {@code path leftKind[*]:left | rightKind:right}, * in the zone: what the tests read. */
    List<String> lines() {
        return List.copyOf(lines);
    }
}
