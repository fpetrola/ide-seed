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
import java.awt.BorderLayout;
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
    private final org.fife.ui.rsyntaxtextarea.RSyntaxTextArea pane = Code.area();
    private final JLabel beforePath = new JLabel(" ");
    private final SideBySidePane sides = new SideBySidePane();
    private final java.awt.CardLayout cards = new java.awt.CardLayout();
    private final JPanel shownPanel = new JPanel(cards);
    private final DefaultListModel<String> alsoListed = new DefaultListModel<>();
    private final JList<String> alsoList = new JList<>(alsoListed);
    private List<Map<String, Object>> also = List.of();
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
        var before = new JPanel(new BorderLayout());
        beforePath.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        before.add(beforePath, BorderLayout.PAGE_START);
        before.add(new org.fife.ui.rtextarea.RTextScrollPane(pane, true), BorderLayout.CENTER);
        shownPanel.add(before, "before");
        var sidesScroll = new JScrollPane(sides);
        sidesScroll.getVerticalScrollBar().setUnitIncrement(16);
        shownPanel.add(sidesScroll, "after");
        alsoList.setVisibleRowCount(3);
        alsoList.setToolTipText("What the step changed away from what it takes: a click shows it");
        alsoList.addListSelectionListener(event -> {
            var i = alsoList.getSelectedIndex();
            if (event.getValueIsAdjusting() || i < 0 || i >= also.size()) return;
            if (!after) {
                after = true;
                display();
            }
            sides.reveal(String.valueOf(also.get(i).get("path")), number(also.get(i).get("row")));
        });
        var right = new JPanel(new BorderLayout());
        right.add(shownPanel, BorderLayout.CENTER);
        var alsoScroll = new JScrollPane(alsoList);
        alsoScroll.setBorder(BorderFactory.createTitledBorder("Also changed"));
        right.add(alsoScroll, BorderLayout.PAGE_END);
        var steps = new JScrollPane(list);
        steps.setPreferredSize(new java.awt.Dimension(Math.round(Zoom.of(300)), 0)); // the code takes the rest
        steps.setMinimumSize(new java.awt.Dimension(0, 0));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, steps, right);
        split.setResizeWeight(0);
        split.setOneTouchExpandable(true);
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
            pane.setText("");
            return;
        }
        var step = ide.record("replay-step", Map.of("n", String.valueOf(steps.get(at).get("n"))));
        where.setText(where());
        what.setText(header(step));
        list.setSelectedIndex(at);
        list.ensureIndexIsVisible(at);
        var changes = (List<Map<String, Object>>) step.get("changes");
        also = (List<Map<String, Object>>) step.get("also");
        alsoListed.clear();
        also.forEach(a -> alsoListed.addElement(a.get("path") + ":" + a.get("line") + "  " + a.get("text")));
        if (after) {
            sides.show(changes);
            cards.show(shownPanel, "after");
            return;
        }
        cards.show(shownPanel, "before");
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

    /** A file with the lines a step takes marked under its highlighting, scrolled to the first. */
    private void mark(String path, String text) {
        beforePath.setText(path + "  (as it was)");
        pane.removeAllLineHighlights();
        pane.setText(text);
        marked.forEach(line -> Code.mark(pane, line - 1, Code.ZONE));
        try {
            pane.setCaretPosition(marked.isEmpty() ? 0 : pane.getLineStartOffset(Math.min(marked.getFirst() - 1, pane.getLineCount() - 1)));
        } catch (BadLocationException nowhere) {
            pane.setCaretPosition(0);
        }
    }

    /** A step as a person reads it: what ran, on what, with what, and for which task. */
    @SuppressWarnings("unchecked")
    private static String header(Map<String, Object> step) {
        var args = (Map<String, Object>) step.get("args");
        var given = String.join(", ", new java.util.TreeMap<>(args).entrySet().stream().map(e -> e.getKey() + " "
                + (e.getValue() instanceof List<?> l ? String.join(", ", l.stream().map(String::valueOf).toList()) : String.valueOf(e.getValue()))).toList());
        var task = String.valueOf(step.get("task"));
        return step.get("what") + ": " + given + (task.isEmpty() ? "" : "   \u00B7 task " + task);
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
        return after ? String.join("\n", sides.lines()) : pane.getText();
    }

    String header() {
        return what.getText();
    }

    List<String> also() {
        return java.util.Collections.list(alsoListed.elements());
    }

    List<Integer> marked() {
        return List.copyOf(marked);
    }
}
