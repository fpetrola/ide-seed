package ide.ui;

import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * What the IDE says, below the editors: the problems, the changes not saved yet, the steps of the last change, what
 * was asked and answered, and results to go to. They change on their own and never take the focus.
 */
final class Views extends JTabbedPane {

    private final DefaultListModel<String> problems = new DefaultListModel<>();
    private final DefaultListModel<String> results = new DefaultListModel<>();
    private final JTextArea changes = text();
    private final JTextArea steps = text();
    private final JTextArea output = text();
    private final Consumer<String> openProblem;
    private final Consumer<String> openResult;

    /** A problem opens where it is ({@code path:line: message}); a result, where the element it names is. */
    Views(Consumer<String> openProblem, Consumer<String> openResult) {
        this.openProblem = openProblem;
        this.openResult = openResult;
        addTab("Problems", new JScrollPane(list(problems, this::openProblem)));
        addTab("Changes", new JScrollPane(changes));
        addTab("Steps", new JScrollPane(steps));
        addTab("Output", new JScrollPane(output));
        addTab("Results", new JScrollPane(list(results, this::openResult)));
    }

    void problems(List<String> lines) {
        problems.clear();
        problems.addAll(lines);
        setTitleAt(0, lines.isEmpty() ? "Problems" : "Problems (" + lines.size() + ")");
    }

    List<String> problems() {
        return Collections.list(problems.elements());
    }

    void changes(String diff) {
        changes.setText(diff);
        changes.setCaretPosition(0);
    }

    String changes() {
        return changes.getText();
    }

    void steps(List<String> lines) {
        steps.setText(String.join("\n", lines));
        steps.setCaretPosition(0);
        setSelectedIndex(2);
    }

    String steps() {
        return steps.getText();
    }

    /** One more line of what was asked and answered. */
    void say(String line) {
        output.append(line + "\n");
    }

    String output() {
        return output.getText();
    }

    void results(String title, List<String> lines) {
        results.clear();
        results.addAll(lines);
        setTitleAt(4, title);
        setSelectedIndex(4);
    }

    List<String> results() {
        return Collections.list(results.elements());
    }

    void openResult(int index) {
        openResult.accept(results.get(index));
    }

    private void openProblem(int index) {
        openProblem.accept(problems.get(index));
    }

    private static JList<String> list(DefaultListModel<String> model, Consumer<Integer> open) {
        var list = new JList<>(model);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && list.getSelectedIndex() >= 0) open.accept(list.getSelectedIndex());
            }
        });
        return list;
    }

    private static JTextArea text() {
        var area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        return area;
    }
}
