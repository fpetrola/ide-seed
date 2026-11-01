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

    /** A problem as the list shows it: where it is, what it says, and whether it is only a warning. */
    record Problem(String path, int line, int column, String message, boolean warning) {

        static Problem of(Map<String, Object> found) {
            return new Problem(String.valueOf(found.get("path")), ((Number) found.get("line")).intValue(),
                    found.get("column") instanceof Number n ? Math.max(1, n.intValue()) : 1,
                    String.valueOf(found.get("message")).lines().findFirst().orElse(""), "WARNING".equals(found.get("severity")));
        }

        @Override
        public String toString() {
            return path + ":" + line + ": " + message;
        }
    }

    private final DefaultListModel<Problem> problems = new DefaultListModel<>();
    private final DefaultListModel<String> results = new DefaultListModel<>();
    private final DiffPane changes = new DiffPane();
    private final DefaultTreeModel steps = new DefaultTreeModel(new DefaultMutableTreeNode("steps"));
    private final JTree stepsTree = new JTree(steps);
    private final JTextArea output = new JTextArea();
    private final List<String> stepLines = new ArrayList<>();
    private final Consumer<Problem> openProblem;
    private final Consumer<String> openResult;

    /** A strip of a few lines under the editors, as IDEs open their tool windows; what it lists scrolls. */
    @Override
    public java.awt.Dimension getPreferredSize() {
        return new java.awt.Dimension(super.getPreferredSize().width, Math.round(Zoom.of(180)));
    }

    /** A problem opens where it is ({@code path:line: message}); a result, where the element it names is. */
    Views(Consumer<Problem> openProblem, Consumer<String> openResult) {
        this.openProblem = openProblem;
        this.openResult = openResult;
        putClientProperty("JTabbedPane.showContentSeparator", true);
        var problemList = list(problems, this::openProblem);
        problemList.setCellRenderer(new ProblemRenderer());
        var resultList = list(results, this::openResult);
        resultList.setCellRenderer(new ResultRenderer());
        stepsTree.setRootVisible(false);
        stepsTree.setShowsRootHandles(true);
        stepsTree.setCellRenderer(new StepRenderer());
        output.setEditable(false);
        output.setFont(Zoom.code(12));
        addTab("Problems", Icons.of("ERROR"), scroll(problemList));
        addTab("Changes", Icons.of("PRIMITIVE"), scroll(changes));
        addTab("Steps", Icons.of("STEP"), scroll(stepsTree));
        addTab("Output", Icons.of("NOTE"), scroll(output));
        addTab("Results", Icons.of("QUESTION"), scroll(resultList));
    }

    /** Writes the code's text at the current zoom. */
    void zoomed() {
        output.setFont(Zoom.code(12));
        changes.setFont(Zoom.code(13));
    }

    /** The problems as IDEs list them, errors first: each with its icon, message, and where it is. */
    void problems(List<Map<String, Object>> found) {
        var listed = found.stream().map(Problem::of).sorted(java.util.Comparator.comparing(Problem::warning)).toList();
        problems.clear();
        problems.addAll(listed);
        var errors = listed.stream().filter(p -> !p.warning()).count();
        setTitleAt(0, listed.isEmpty() ? "Problems" : "Problems  " + errors + " errors, " + (listed.size() - errors) + " warnings");
    }

    /** The problems listed, errors first, as {@code path:line: message}. */
    List<String> problems() {
        return Collections.list(problems.elements()).stream().map(Problem::toString).toList();
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

    private static <T> JList<T> list(DefaultListModel<T> model, Consumer<Integer> open) {
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
            var problem = (Problem) value;
            var html = "<html>" + problem.message().replace("<", "&lt;") + "&nbsp;&nbsp;" + gray(problem.path() + ":" + problem.line()) + "</html>";
            super.getListCellRendererComponent(list, html, index, selected, focus);
            setIcon(Icons.of(problem.warning() ? "WARNING" : "ERROR"));
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
