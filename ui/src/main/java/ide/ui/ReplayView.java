package ide.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A plan seen again, step by step, as the IDE kept it (replay): each step first as it will run, what it is given and
 * the place of the code it takes marked in the file as it was, then what it changed, as a diff of each file. Forward
 * and back go through both; going on from a step brings the files back to it (replay-continue), the steps after it
 * discarded. It shows what the IDE answers and decides nothing.
 */
final class ReplayView extends JPanel {

    private final Ide ide;
    private final Runnable changed;
    private final DefaultListModel<String> listed = new DefaultListModel<>();
    private final JList<String> list = new JList<>(listed);
    private final JLabel where = new JLabel(" ");
    private final JLabel what = new JLabel(" ");
    private final DiffPane pane = new DiffPane();
    private List<Map<String, Object>> steps = List.of();
    private Optional<String> plan = Optional.empty();
    private int at; // the step shown, from 0
    private boolean after; // its changes, rather than what it takes
    private final List<Integer> marked = new ArrayList<>();

    /** {@code changed}: the files changed (going on from a step). */
    ReplayView(Ide ide, Runnable changed) {
        super(new BorderLayout());
        this.ide = ide;
        this.changed = changed;
        var previous = button("◀ Back", "The step before, or what this one takes");
        previous.addActionListener(event -> previous());
        var next = button("Forward ▶", "What this step changed, or the next step");
        next.addActionListener(event -> next());
        var goOn = button("Go on from here", "Bring the files back to how this step left them; the steps after it are discarded");
        goOn.addActionListener(event -> goOn());
        var bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bar.add(previous);
        bar.add(next);
        bar.add(where);
        bar.add(goOn);
        var top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.PAGE_START);
        top.add(what, BorderLayout.PAGE_END);
        what.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && list.getSelectedIndex() >= 0 && list.getSelectedIndex() != at) {
                at = list.getSelectedIndex();
                after = false;
                display();
            }
        });
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(pane));
        split.setResizeWeight(0.3);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(top, BorderLayout.PAGE_START);
        add(split, BorderLayout.CENTER);
    }

    private static JButton button(String text, String tip) {
        var button = new JButton(text);
        button.setToolTipText(tip);
        button.putClientProperty("JButton.buttonType", "borderless");
        return button;
    }

    /** The steps of a plan (every step, with none), from the first, as it will run. */
    @SuppressWarnings("unchecked")
    void load(Optional<String> plan) {
        this.plan = plan;
        steps = (List<Map<String, Object>>) ide.record("replay", plan.map(p -> Map.of("plan", p)).orElse(Map.of())).get("steps");
        listed.clear();
        steps.forEach(step -> listed.addElement(line(step)));
        at = 0;
        after = false;
        display();
    }

    void next() {
        if (steps.isEmpty()) return;
        if (!after) after = true;
        else if (at < steps.size() - 1) {
            at++;
            after = false;
        }
        display();
    }

    void previous() {
        if (steps.isEmpty()) return;
        if (after) after = false;
        else if (at > 0) {
            at--;
            after = true;
        }
        display();
    }

    /** The files as the step shown left them; the steps after it discarded. */
    void goOn() {
        if (steps.isEmpty()) return;
        var n = steps.get(at).get("n");
        ide.call("replay-continue", Map.of("n", String.valueOf(n)));
        changed.run();
        var shown = at;
        load(plan);
        at = Math.min(shown, steps.size() - 1);
        after = true;
        display();
    }

    @SuppressWarnings("unchecked")
    private void display() {
        marked.clear();
        if (steps.isEmpty()) {
            where.setText("no steps");
            what.setText(" ");
            pane.show("");
            return;
        }
        var step = ide.record("replay-step", Map.of("n", String.valueOf(steps.get(at).get("n"))));
        where.setText(where());
        what.setText(line(step));
        list.setSelectedIndex(at);
        list.ensureIndexIsVisible(at);
        var changes = (List<Map<String, Object>>) step.get("changes");
        if (after) {
            pane.show(String.join("", changes.stream().map(c -> String.valueOf(c.get("diff"))).toList()));
            return;
        }
        // what it takes: the first place it names, in its file as it was; else the first file it changed
        var places = (List<Map<String, Object>>) step.get("where");
        var path = places.isEmpty() ? String.valueOf(changes.getFirst().get("path")) : String.valueOf(places.getFirst().get("path"));
        var text = changes.stream().filter(c -> path.equals(c.get("path"))).map(c -> c.get("before")).findFirst()
                .map(before -> before == null ? "" : String.valueOf(before)).orElse("");
        for (var place : places) {
            if (!path.equals(place.get("path"))) continue;
            for (var line = number(place.get("line")); line <= number(place.get("endLine")); line++) {
                if (!marked.contains(line)) marked.add(line);
            }
        }
        marked.sort(null);
        mark(path, text);
    }

    /** A file with the lines a step takes marked, scrolled to the first. */
    private void mark(String path, String text) {
        pane.setText("");
        var document = pane.getStyledDocument();
        var first = -1;
        try {
            var header = new SimpleAttributeSet();
            StyleConstants.setBold(header, true);
            StyleConstants.setForeground(header, new Color(0xCCCCCC));
            document.insertString(0, path + "\n", header);
            var lines = text.lines().toList();
            for (var i = 0; i < lines.size(); i++) {
                var style = new SimpleAttributeSet();
                StyleConstants.setForeground(style, new Color(0xA9B7C6));
                if (marked.contains(i + 1)) {
                    StyleConstants.setBackground(style, new Color(0x52503A));
                    if (first < 0) first = document.getLength();
                }
                document.insertString(document.getLength(), lines.get(i) + "\n", style);
            }
        } catch (BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
        pane.setCaretPosition(Math.max(first, 0));
    }

    @SuppressWarnings("unchecked")
    private static String line(Map<String, Object> step) {
        var args = (Map<String, Object>) step.get("args");
        var given = String.join("  ", new java.util.TreeMap<>(args).entrySet().stream().map(e -> e.getKey() + "="
                + (e.getValue() instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : String.valueOf(e.getValue()))).toList());
        var task = String.valueOf(step.get("task"));
        return step.get("n") + " " + step.get("what") + "  " + given + (task.isEmpty() ? "" : "  [" + task + "]")
                + (Boolean.TRUE.equals(step.get("discarded")) ? "  (discarded)" : "");
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    // ---- what the tests read

    List<String> steps() {
        return java.util.Collections.list(listed.elements());
    }

    String where() {
        return steps.isEmpty() ? "no steps" : "step " + (at + 1) + " of " + steps.size() + ", " + (after ? "after" : "before");
    }

    String shown() {
        return pane.getText();
    }

    List<Integer> marked() {
        return List.copyOf(marked);
    }
}
