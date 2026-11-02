package ide.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The plan being followed, beside the project, where it can be seen while the terminal works: its title, how far it
 * got, and its tasks, each with what state it is in and its note. It only shows what the IDE answers.
 */
final class PlanView extends JPanel {

    /** A task as the list shows it. */
    record Task(int n, String text, String state, String note) {

        @SuppressWarnings("unchecked")
        static Task of(Object listed) {
            var task = (Map<String, Object>) listed;
            return new Task(((Number) task.get("n")).intValue(), String.valueOf(task.get("text")),
                    String.valueOf(task.get("state")), String.valueOf(task.get("note")));
        }

        @Override
        public String toString() {
            return n + ". [" + state + "] " + text + (note.isEmpty() ? "" : " - " + note);
        }
    }

    private final JLabel title = new JLabel("No plan");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultListModel<Task> tasks = new DefaultListModel<>();

    PlanView() {
        super(new BorderLayout());
        var heading = new JPanel(new BorderLayout(0, 4));
        heading.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        title.putClientProperty("FlatLaf.styleClass", "h4");
        heading.add(title, BorderLayout.PAGE_START);
        progress.setStringPainted(true);
        progress.setVisible(false);
        heading.add(progress, BorderLayout.PAGE_END);
        add(heading, BorderLayout.PAGE_START);
        var list = new JList<>(tasks);
        list.setCellRenderer(new TaskRenderer());
        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
    }

    /** The plan as the {@code plan} capability answers it. */
    void show(Map<String, Object> plan) {
        var listed = ((List<?>) plan.get("tasks")).stream().map(Task::of).toList();
        tasks.clear();
        tasks.addAll(listed);
        var named = String.valueOf(plan.get("title"));
        title.setText(named.isEmpty() && listed.isEmpty() ? "No plan" : named);
        var done = ((Number) plan.get("done")).intValue();
        var of = ((Number) plan.get("of")).intValue();
        progress.setVisible(of > 0);
        progress.setMaximum(Math.max(1, of));
        progress.setValue(done);
        progress.setString(done + " of " + of + " done");
    }

    /** The title, how far it got, and each task as {@code n. [state] text - note}. */
    List<String> lines() {
        var lines = new java.util.ArrayList<String>();
        lines.add(title.getText() + (progress.isVisible() ? " (" + progress.getString() + ")" : ""));
        Collections.list(tasks.elements()).forEach(task -> lines.add(task.toString()));
        return lines;
    }

    private static final class TaskRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
            var task = (Task) value;
            var note = task.note().isEmpty() ? "" : "<br><font color='#808080'>" + escape(task.note()) + "</font>";
            var text = task.state().equals("done") ? "<font color='#808080'>" + escape(task.text()) + "</font>" : escape(task.text());
            super.getListCellRendererComponent(list, "<html>" + (task.state().equals("doing") ? "<b>" + text + "</b>" : text)
                    + note + "</html>", index, selected, focus);
            setIcon(Icons.of(task.state().toUpperCase(java.util.Locale.ROOT)));
            setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            if (!selected) setBackground(UIManager.getColor("List.background"));
            return this;
        }

        private static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
