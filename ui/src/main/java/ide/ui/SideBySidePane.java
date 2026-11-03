package ide.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A file of a step side by side, as the IDE aligned it (replay-step's rows), one file at a time, as IntelliJ's diff
 * shows one: before on the left, after on the right, row by row, highlighted as Java. Under the highlighting, what went
 * is red, what came green, a changed line amber on both sides, moved lines blue on both, and the lines the step takes
 * yellow where they stayed, marked ▌ in the margin. A new file has nothing on the left. Each side is as high as the
 * view and scrolls on its own, both sides together, up and down and sideways (its bars, the wheel, Shift+wheel,
 * anywhere over either side); Ctrl+wheel zooms.
 */
final class SideBySidePane extends JPanel {

    private static final Color BACKGROUND = new Color(0x2B2B2B);

    /** A row of a file. */
    record Place(String path, int row) {
    }

    private final JLabel header = new JLabel(" ");
    private final JPanel columns = new JPanel(new GridLayout(1, 2, 2, 0));
    private final List<String> lines = new ArrayList<>();
    private List<Map<String, Object>> shown = List.of();
    private String file = "";
    private RSyntaxTextArea left;
    private JScrollPane leftSide;
    private Place first;
    private float size = Zoom.code(13).getSize2D();

    SideBySidePane() {
        super(new BorderLayout());
        setBackground(BACKGROUND);
        header.setForeground(new Color(0xCCCCCC));
        header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        columns.setBackground(BACKGROUND);
        add(header, BorderLayout.PAGE_START);
        add(columns, BorderLayout.CENTER);
    }

    /** The files of a step; the one seen first is where what it takes is, else what it changed first. */
    @SuppressWarnings("unchecked")
    void show(List<Map<String, Object>> changes) {
        shown = changes;
        file = "";
        lines.clear();
        for (var change : changes) {
            var path = String.valueOf(change.get("path"));
            for (var row : (List<Map<String, Object>>) change.get("rows")) {
                lines.add(path + " " + row.get("leftKind") + (Boolean.TRUE.equals(row.get("zone")) ? "*" : "") + ":" + row.get("left")
                        + " | " + row.get("rightKind") + ":" + row.get("right"));
            }
        }
        first = firstOf(changes, true);
        if (first == null) first = firstOf(changes, false);
        if (first != null) reveal(first.path(), first.row());
        else {
            columns.removeAll();
            left = null;
            header.setText(" ");
            revalidate();
            repaint();
        }
    }

    /** Shows a file of the step, scrolled so that one of its rows is near the top, from the start of its lines. */
    void reveal(String path, int row) {
        if (!path.equals(file)) build(path);
        if (left == null) return;
        scrollTo(row);
        SwingUtilities.invokeLater(() -> scrollTo(row)); // again, once laid out
    }

    private void scrollTo(int row) {
        if (left == null) return;
        try {
            var line = Math.max(0, Math.min(row, left.getLineCount() - 1));
            var at = left.modelToView2D(left.getLineStartOffset(line));
            if (at == null) return;
            leftSide.getVerticalScrollBar().setValue(Math.max(0, (int) at.getY() - (int) at.getHeight() * 3));
            leftSide.getHorizontalScrollBar().setValue(0);
        } catch (javax.swing.text.BadLocationException | RuntimeException nowhere) {
            // a row that is not there: stay
        }
    }

    @SuppressWarnings("unchecked")
    private void build(String path) {
        var change = shown.stream().filter(c -> path.equals(String.valueOf(c.get("path")))).findFirst().orElse(null);
        columns.removeAll();
        file = path;
        left = null;
        if (change == null) return;
        var status = String.valueOf(change.get("status"));
        header.setText(path + (status.equals("changed") ? "" : "  (" + status + ")"));
        var rows = (List<Map<String, Object>>) change.get("rows");
        var leftNumbers = new StringBuilder();
        var leftText = new StringBuilder();
        var rightNumbers = new StringBuilder();
        var rightText = new StringBuilder();
        for (var row : rows) {
            var zone = Boolean.TRUE.equals(row.get("zone"));
            leftNumbers.append(zone ? "▌" : " ").append(number(row.get("leftLine"))).append(' ').append('\n');
            leftText.append(row.get("left")).append('\n');
            rightNumbers.append(' ').append(number(row.get("rightLine"))).append(' ').append('\n');
            rightText.append(row.get("right")).append('\n');
        }
        var leftArea = sized(Code.area());
        leftArea.setText(leftText.toString());
        var right = sized(Code.area());
        right.setText(rightText.toString());
        for (var i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            mark(leftArea, i, String.valueOf(row.get("leftKind")), Boolean.TRUE.equals(row.get("zone")));
            mark(right, i, String.valueOf(row.get("rightKind")), false);
        }
        // both sides as wide and as high: one model each way scrolls both
        var widest = Math.max(leftArea.getPreferredSize().width, right.getPreferredSize().width);
        var highest = Math.max(leftArea.getPreferredSize().height, right.getPreferredSize().height);
        leftArea.setPreferredSize(new Dimension(widest, highest));
        right.setPreferredSize(new Dimension(widest, highest));
        var sideOfLeft = side(leftArea, sized(numbers(leftNumbers.toString())));
        var rightSide = side(right, sized(numbers(rightNumbers.toString())));
        rightSide.getVerticalScrollBar().setModel(sideOfLeft.getVerticalScrollBar().getModel());
        rightSide.getHorizontalScrollBar().setModel(sideOfLeft.getHorizontalScrollBar().getModel());
        sideOfLeft.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER); // one bar up and down: the right one
        columns.add(sideOfLeft);
        columns.add(rightSide);
        left = leftArea;
        leftSide = sideOfLeft;
        revalidate();
        repaint();
    }

    /** A side: as high as the view, its line numbers beside it, a bar whenever its lines do not fit. */
    private static JScrollPane side(RSyntaxTextArea area, RSyntaxTextArea numbers) {
        var side = new JScrollPane(area, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        side.setRowHeaderView(numbers);
        side.setBorder(BorderFactory.createEmptyBorder());
        side.getViewport().setBackground(BACKGROUND);
        side.getVerticalScrollBar().setUnitIncrement(16);
        side.getHorizontalScrollBar().setUnitIncrement(16);
        side.setMinimumSize(new Dimension(0, 0));
        side.setPreferredSize(new Dimension(10, 10));
        side.setWheelScrollingEnabled(false); // the wheel is the areas' own, below
        return side;
    }

    /** At the size zoomed to; the wheel over it scrolls both sides, Shift+wheel sideways, Ctrl+wheel zooms. */
    private RSyntaxTextArea sized(RSyntaxTextArea area) {
        area.setFont(area.getFont().deriveFont(size));
        area.addMouseWheelListener(event -> {
            event.consume();
            if (leftSide == null) return;
            if (event.isControlDown()) {
                size = Math.max(8, Math.min(32, size - (float) event.getPreciseWheelRotation()));
                var seen = file;
                var top = leftSide.getVerticalScrollBar().getValue();
                var aside = leftSide.getHorizontalScrollBar().getValue();
                build(seen);
                SwingUtilities.invokeLater(() -> {
                    leftSide.getVerticalScrollBar().setValue(top);
                    leftSide.getHorizontalScrollBar().setValue(aside);
                });
                return;
            }
            // a wheel that turns sideways (a touchpad) or Shift: across; else up and down; both sides share the bars
            var across = event.isShiftDown();
            var bar = across ? leftSide.getHorizontalScrollBar() : leftSide.getVerticalScrollBar();
            bar.setValue(bar.getValue() + (int) Math.round(event.getPreciseWheelRotation() * 16 * 3));
        });
        return area;
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

    private static String number(Object line) {
        var n = line instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(line));
        return n == 0 ? "    " : String.format("%4d", n);
    }

    /** Where a step is seen first: the first row of what it takes, else the first row it changed. */
    Place first() {
        return first;
    }

    /** The file shown. */
    String file() {
        return file;
    }

    /** Scrolls as the wheel would over either side: what the tests do. */
    void wheel(boolean shift, int notches) {
        var bar = shift ? leftSide.getHorizontalScrollBar() : leftSide.getVerticalScrollBar();
        bar.setValue(bar.getValue() + notches * 16 * 3);
    }

    /** Where both sides are scrolled to: across, then down. */
    int[] scrolled() {
        return leftSide == null ? new int[]{0, 0}
                : new int[]{leftSide.getHorizontalScrollBar().getValue(), leftSide.getVerticalScrollBar().getValue()};
    }

    /** The first changed row of each file a step changed, in order: where to go to see that file. */
    @SuppressWarnings("unchecked")
    List<Place> files() {
        var files = new ArrayList<Place>();
        for (var change : shown) {
            var rows = (List<Map<String, Object>>) change.get("rows");
            var row = 0;
            for (var i = 0; i < rows.size(); i++) {
                if (!"same".equals(rows.get(i).get("leftKind")) || !"same".equals(rows.get(i).get("rightKind"))) {
                    row = i;
                    break;
                }
            }
            files.add(new Place(String.valueOf(change.get("path")), row));
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    private static Place firstOf(List<Map<String, Object>> changes, boolean zone) {
        for (var change : changes) {
            var rows = (List<Map<String, Object>>) change.get("rows");
            for (var i = 0; i < rows.size(); i++) {
                var row = rows.get(i);
                var hit = zone ? Boolean.TRUE.equals(row.get("zone"))
                        : !"same".equals(row.get("leftKind")) || !"same".equals(row.get("rightKind"));
                if (hit) return new Place(String.valueOf(change.get("path")), i);
            }
        }
        return null;
    }

    /** Each row of every file as {@code path leftKind[*]:left | rightKind:right}, * in the zone: what the tests read. */
    List<String> lines() {
        return List.copyOf(lines);
    }
}
