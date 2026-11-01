package ide.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the IDE says, below the editors, as tool windows: the problems, the changes not saved yet as a diff, the
 * steps of the last change as a tree, what was asked and answered, and results to go to. They change on their own and
 * never take the focus.
 */
final class Views extends JTabbedPane {

    private static final Map<String, String> SIGILS = Map.of(".", "STEP", "'", "PRIMITIVE", "?", "QUESTION", "!", "ANSWER",
            "X", "DROPPED", ":", "ASSUMED");

    private final DefaultListModel<String> problems = new DefaultListModel<>();
    private final DefaultListModel<String> results = new DefaultListModel<>();
    private final DiffPane changes = new DiffPane();
    private final DefaultTreeModel steps = new DefaultTreeModel(new DefaultMutableTreeNode("steps"));
    private final JTree stepsTree = new JTree(steps);
    private final JTextArea output = new JTextArea();
    private final List<String> stepLines = new ArrayList<>();
    private final Consumer<String> openProblem;
    private final Consumer<String> openResult;

    /** A problem opens where it is ({@code path:line: message}); a result, where the element it names is. */
    Views(Consumer<String> openProblem, Consumer<String> openResult) {
        this.openProblem = openProblem;
        this.openResult = openResult;
        putClientProperty("JTabbedPane.tabHeight", 28);
        putClientProperty("JTabbedPane.showContentSeparator", true);
        var problemList = list(problems, this::openProblem);
        problemList.setCellRenderer(new ProblemRenderer());
        var resultList = list(results, this::openResult);
        resultList.setCellRenderer(new ResultRenderer());
        stepsTree.setRootVisible(false);
        stepsTree.setShowsRootHandles(true);
        stepsTree.setCellRenderer(new StepRenderer());
        output.setEditable(false);
        output.setFont(new Font("JetBrains Mono", Font.PLAIN, 12));
        addTab("Problems", Icons.of("ERROR"), scroll(problemList));
        addTab("Changes", Icons.of("PRIMITIVE"), scroll(changes));
        addTab("Steps", Icons.of("STEP"), scroll(stepsTree));
        addTab("Output", Icons.of("NOTE"), scroll(output));
        addTab("Results", Icons.of("QUESTION"), scroll(resultList));
    }

    void problems(List<String> lines) {
        problems.clear();
        problems.addAll(lines);
        setTitleAt(0, lines.isEmpty() ? "Problems" : "Problems  " + lines.size());
    }

    List<String> problems() {
        return Collections.list(problems.elements());
    }

    void changes(String diff) {
        changes.show(diff);
    }

    String changes() {
        return changes.getText();
    }

    /** The steps of a change, as the trace writes them: two spaces a level, a sigil, a label. */
    void steps(List<String> lines) {
        stepLines.clear();
        stepLines.addAll(lines);
        var root = new DefaultMutableTreeNode("steps");
        var open = new ArrayList<DefaultMutableTreeNode>();
        open.add(root);
        for (var line : lines) {
            var depth = (line.length() - line.stripLeading().length()) / 2;
            var node = new DefaultMutableTreeNode(line.strip());
            while (open.size() > depth + 1) open.removeLast();
            open.getLast().add(node);
            open.add(node);
        }
        steps.setRoot(root);
        for (var row = 0; row < stepsTree.getRowCount(); row++) stepsTree.expandRow(row);
        setSelectedIndex(2);
    }

    String steps() {
        return String.join("\n", stepLines);
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

    private static JScrollPane scroll(JComponent view) {
        var scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private static JList<String> list(DefaultListModel<String> model, Consumer<Integer> open) {
        var list = new JList<>(model);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && list.getSelectedIndex() >= 0) open.accept(list.getSelectedIndex());
            }
        });
        return list;
    }

    private static String gray(String text) {
        return "<font color='#808080'>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</font>";
    }

    /** A problem: its icon, its message, and in gray its file and line. */
    private static final class ProblemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            var parts = String.valueOf(value).split(":", 3);
            var html = parts.length < 3 ? String.valueOf(value)
                    : "<html>" + parts[2].strip().replace("<", "&lt;") + "&nbsp;&nbsp;" + gray(parts[0] + ":" + parts[1]) + "</html>";
            super.getListCellRendererComponent(list, html, index, selected, focus);
            setIcon(Icons.of("ERROR"));
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return this;
        }
    }

    /** A result names an element: its icon, its name, and in gray where it is. */
    private static final class ResultRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            var line = String.valueOf(value);
            var indent = line.length() - line.stripLeading().length();
            var element = line.strip().replaceFirst("^> ", "");
            var hash = element.indexOf('#');
            var name = hash < 0 ? element.substring(element.lastIndexOf('.') + 1) : element.substring(hash + 1);
            var where = hash < 0 ? element.substring(0, Math.max(0, element.lastIndexOf('.'))) : element.substring(0, hash);
            var focusMark = line.strip().startsWith("> ") ? "<b>" : "";
            var html = "<html>" + "&nbsp;".repeat(indent * 2) + focusMark + name.replace("<", "&lt;") + (focusMark.isEmpty() ? "" : "</b>")
                    + "&nbsp;&nbsp;" + gray(where) + "</html>";
            super.getListCellRendererComponent(list, html, index, selected, focus);
            setIcon(Icons.of(hash < 0 ? "CLASS" : element.contains("(") ? "METHOD" : "FIELD"));
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            return this;
        }
    }

    /** A step: the icon of its sigil, and what it did. */
    private static final class StepRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            var text = String.valueOf(((DefaultMutableTreeNode) value).getUserObject());
            var space = text.indexOf(' ');
            var sigil = space < 0 ? "" : text.substring(0, space);
            var kind = SIGILS.get(sigil);
            super.getTreeCellRendererComponent(tree, kind == null ? text : text.substring(space + 1), selected, expanded, leaf, row, focus);
            setIcon(Icons.of(kind == null ? "NOTE" : kind));
            return this;
        }
    }
}
