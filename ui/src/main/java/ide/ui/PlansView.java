package ide.ui;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.event.HyperlinkEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The plans a person and Claude follow, beside the editors: which plan, how far it got and what can be said of it; its
 * lines of action as a tree; and the task chosen, with everything it has, its links into the code and its timeline,
 * and what can be said of it. It only shows what the IDE answers; what the person says goes to {@link Actions}.
 */
final class PlansView extends JPanel {

    /** What the view asks of the window. */
    interface Actions {
        /** The person says something of a plan or a task: pause, resume, comment, replan or switch. */
        void steer(String kind, String plan, String task, String text);

        /** Opens lines of a file. */
        void open(String path, int line, int endLine);

        /** A line of text the person writes, if they do. */
        Optional<String> ask(String title);
    }

    /** A plan as the chooser lists it. */
    record Listed(String id, String title, String state, int done, int of) {
        @Override
        public String toString() {
            return title + " (" + state + ") " + done + "/" + of;
        }
    }

    /** A task as the tree shows it. */
    record Line(String id, String title, String state, int depth, int done, int of, List<String> waiting) {
        @Override
        public String toString() {
            return id + " [" + state + "] " + title + (of > 0 ? " " + done + "/" + of : "")
                    + (waiting.isEmpty() ? "" : " (waits " + String.join(", ", waiting) + ")");
        }
    }

    private final Ide ide;
    private final Actions actions;
    private final JComboBox<Listed> chooser = new JComboBox<>();
    private final JLabel heading = new JLabel("No plan");
    private final JProgressBar progress = new JProgressBar();
    private final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(model);
    private final JEditorPane details = new JEditorPane("text/html", "");
    private final JButton switchTo = button("Go on with this plan", "switch");
    private final JButton planPause = button("Pause plan", "pause-plan");
    private final JButton planResume = button("Resume plan", "resume-plan");
    private final JButton planReplan = button("Replan…", "replan-plan");
    private final JButton taskPause = button("Pause", "pause");
    private final JButton taskResume = button("Resume", "resume");
    private final JButton taskComment = button("Comment…", "comment");
    private final JButton taskReplan = button("Replan…", "replan");
    private String shown = "";
    private String active = "";
    private String chosenTask = "";
    private boolean filling;
    private List<String> detail = List.of();
    private final List<Map<String, Object>> links = new ArrayList<>();

    PlansView(Ide ide, Actions actions) {
        super(new BorderLayout());
        this.ide = ide;
        this.actions = actions;
        var top = new JPanel(new BorderLayout(0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        chooser.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Listed listed) {
                    setText(listed.title() + "  " + listed.done() + "/" + listed.of());
                    setIcon(icon(listed.state().equals("active") ? "doing" : listed.state()));
                }
                return this;
            }
        });
        chooser.addActionListener(event -> {
            if (!filling && chooser.getSelectedItem() instanceof Listed listed) choose(listed.id());
        });
        top.add(chooser, BorderLayout.PAGE_START);
        heading.putClientProperty("FlatLaf.styleClass", "h4");
        heading.setVerticalAlignment(JLabel.TOP);
        top.add(heading, BorderLayout.CENTER);
        var planBar = new JPanel(new BorderLayout(0, 4));
        progress.setStringPainted(true);
        planBar.add(progress, BorderLayout.PAGE_START);
        planBar.add(row(switchTo, planPause, planResume, planReplan), BorderLayout.CENTER);
        top.add(planBar, BorderLayout.PAGE_END);
        add(top, BorderLayout.PAGE_START);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new LineRenderer());
        tree.addTreeSelectionListener(event -> {
            if (!filling && tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof Line line) {
                select(line.id());
            }
        });
        var treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());

        details.setEditable(false);
        details.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        details.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) follow(Integer.parseInt(event.getDescription()));
        });
        var detailPanel = new JPanel(new BorderLayout());
        var detailScroll = new JScrollPane(details);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailPanel.add(detailScroll, BorderLayout.CENTER);
        detailPanel.add(row(taskPause, taskResume, taskComment, taskReplan), BorderLayout.PAGE_END);

        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, detailPanel);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        showPlan(Map.of());
        showTask(null);
    }

    private JButton button(String text, String kind) {
        var button = new JButton(text);
        button.putClientProperty("JButton.buttonType", "borderless");
        button.addActionListener(event -> act(kind));
        return button;
    }

    private static JPanel row(JButton... buttons) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        for (var button : buttons) row.add(button);
        return row;
    }

    // ---- what the window does

    /** Everything again as the IDE answers it now; the plan shown is the active one if that changed. */
    @SuppressWarnings("unchecked")
    void refresh() {
        var listed = ((List<Map<String, Object>>) ide.call("plans", Map.of())).stream().map(p -> new Listed(String.valueOf(p.get("id")),
                String.valueOf(p.get("title")), String.valueOf(p.get("state")), number(p.get("done")), number(p.get("of")))).toList();
        var nowActive = listed.stream().filter(p -> p.state().equals("active")).map(Listed::id).findFirst().orElse(active);
        if (!nowActive.equals(active) || listed.stream().noneMatch(p -> p.id().equals(shown))) shown = nowActive;
        active = nowActive;
        filling = true;
        chooser.removeAllItems();
        listed.forEach(chooser::addItem);
        listed.stream().filter(p -> p.id().equals(shown)).findFirst().ifPresent(chooser::setSelectedItem);
        filling = false;
        showPlan(shown.isEmpty() ? Map.of() : (Map<String, Object>) ide.call("plan", Map.of("plan", shown)));
        if (!chosenTask.isEmpty()) select(chosenTask);
    }

    /** Shows another plan, not making it the one that goes on. */
    void choose(String plan) {
        shown = plan;
        chosenTask = "";
        refresh();
    }

    /** Shows a task: everything it has. */
    @SuppressWarnings("unchecked")
    void select(String task) {
        Map<String, Object> found;
        try {
            found = (Map<String, Object>) ide.call("task", Map.of("task", task));
        } catch (RuntimeException gone) {
            chosenTask = "";
            showTask(null);
            return;
        }
        chosenTask = task;
        showTask(found);
        filling = true;
        var root = (DefaultMutableTreeNode) model.getRoot();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof Line line && line.id().equals(task)) tree.setSelectionPath(new TreePath(node.getPath()));
        }
        filling = false;
    }

    /** Opens the code the chosen task's link {@code n} (from 0) is about. */
    void follow(int n) {
        if (n < 0 || n >= links.size()) return;
        var link = links.get(n);
        actions.open(String.valueOf(link.get("path")), number(link.get("line")), number(link.get("endLine")));
    }

    /**
     * What the person says: of the chosen task {@code pause}, {@code resume}, {@code comment}, {@code replan}; of the
     * plan shown {@code switch}, {@code pause-plan}, {@code resume-plan}, {@code replan-plan}.
     */
    void act(String kind) {
        var ofPlan = kind.endsWith("-plan") || kind.equals("switch");
        if (ofPlan ? shown.isEmpty() : chosenTask.isEmpty()) return;
        var said = kind.replace("-plan", "");
        var text = "";
        if (said.equals("comment") || said.equals("replan")) {
            var asked = actions.ask(said.equals("comment") ? "Comment for Claude on " + chosenTask : "What should change in "
                    + (ofPlan ? "the plan" : chosenTask));
            if (asked.isEmpty() || asked.get().isBlank()) return;
            text = asked.get();
        }
        actions.steer(said, shown, ofPlan ? "" : chosenTask, text);
    }

    // ---- what it shows

    @SuppressWarnings("unchecked")
    private void showPlan(Map<String, Object> plan) {
        var root = new DefaultMutableTreeNode();
        var tasks = (List<Map<String, Object>>) plan.getOrDefault("tasks", List.of());
        var at = new LinkedHashMap<String, DefaultMutableTreeNode>();
        for (var task : tasks) {
            var line = new Line(String.valueOf(task.get("id")), String.valueOf(task.get("title")), String.valueOf(task.get("state")),
                    number(task.get("depth")), number(task.get("done")), number(task.get("of")),
                    ((List<?>) task.get("waiting")).stream().map(String::valueOf).toList());
            var node = new DefaultMutableTreeNode(line);
            at.getOrDefault(String.valueOf(task.get("parent")), root).add(node);
            at.put(line.id(), node);
        }
        filling = true;
        model.setRoot(root);
        for (var row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
        filling = false;
        var named = String.valueOf(plan.getOrDefault("title", ""));
        var state = String.valueOf(plan.getOrDefault("state", ""));
        heading.setText(named.isEmpty() ? "No plan" : "<html>" + escape(named) + "<br><font size='-1' color='#808080'>"
                + escape(String.valueOf(plan.getOrDefault("description", ""))) + "</font></html>");
        heading.setToolTipText(named);
        var done = number(plan.getOrDefault("done", 0));
        var of = number(plan.getOrDefault("of", 0));
        progress.setVisible(of > 0);
        progress.setMaximum(Math.max(1, of));
        progress.setValue(done);
        progress.setString(done + " of " + of + " done");
        headingLine = named.isEmpty() ? "No plan" : named + " (" + state + ") " + done + " of " + of + " done";
        switchTo.setVisible(!named.isEmpty() && !shown.equals(active));
        planPause.setVisible(state.equals("active"));
        planResume.setVisible(state.equals("paused"));
        planReplan.setVisible(!named.isEmpty());
    }

    private String headingLine = "No plan";

    @SuppressWarnings("unchecked")
    private void showTask(Map<String, Object> task) {
        links.clear();
        var lines = new ArrayList<String>();
        var html = new StringBuilder("<html><body style='font-family:sans-serif;margin:6px'>");
        if (task == null) {
            html.append("<font color='#808080'>Choose a task to see it.</font>");
        } else {
            var state = String.valueOf(task.get("state"));
            lines.add(task.get("id") + " " + task.get("title"));
            html.append("<b>").append(escape(task.get("id") + "  " + task.get("title"))).append("</b><br>");
            var fields = new LinkedHashMap<String, String>();
            fields.put("state", state);
            fields.put("plan", String.valueOf(task.get("plan")));
            if (!String.valueOf(task.get("parent")).isEmpty()) fields.put("part of", String.valueOf(task.get("parent")));
            if (!((List<?>) task.get("subtasks")).isEmpty()) fields.put("subtasks", joined(task.get("subtasks")));
            if (!((List<?>) task.get("after")).isEmpty()) fields.put("after", joined(task.get("after")));
            if (!((List<?>) task.get("waiting")).isEmpty()) fields.put("waits for", joined(task.get("waiting")));
            html.append("<table cellpadding='1' cellspacing='0'>");
            fields.forEach((name, value) -> {
                lines.add(name + ": " + value);
                html.append("<tr><td><font color='#808080'>").append(name).append("</font></td><td>").append(escape(value)).append("</td></tr>");
            });
            html.append("</table>");
            var description = String.valueOf(task.get("description"));
            if (!description.isEmpty()) {
                lines.add(description);
                html.append("<p>").append(escape(description).replace("\n", "<br>")).append("</p>");
            }
            var linked = (List<Map<String, Object>>) task.get("links");
            if (!linked.isEmpty()) {
                html.append("<p><font color='#808080'>Code</font><br>");
                for (var link : linked) {
                    var place = link.get("path") + ":" + link.get("line") + (number(link.get("endLine")) > number(link.get("line"))
                            ? "-" + link.get("endLine") : "");
                    var name = String.valueOf(link.get("element")).isEmpty() ? place : link.get("element") + "  " + place;
                    var note = String.valueOf(link.get("note"));
                    lines.add("link " + links.size() + ": " + name + (note.isEmpty() ? "" : " - " + note));
                    html.append("<a href='").append(links.size()).append("'>").append(escape(name)).append("</a>")
                            .append(note.isEmpty() ? "" : " <font color='#808080'>" + escape(note) + "</font>").append("<br>");
                    links.add(link);
                }
                html.append("</p>");
            }
            var timeline = (List<Map<String, Object>>) task.get("timeline");
            if (!timeline.isEmpty()) {
                html.append("<p><font color='#808080'>Timeline</font><br>");
                for (var entry : timeline.reversed()) {
                    var at = String.valueOf(entry.get("at"));
                    var time = at.length() >= 16 ? at.substring(11, 16) : at;
                    var who = String.valueOf(entry.get("who"));
                    lines.add(time + " " + who + " " + entry.get("kind") + ": " + entry.get("text"));
                    html.append("<font color='#808080'>").append(time).append("</font> <b>").append(who.equals("person") ? "you" : "Claude")
                            .append("</b> <font color='#808080'>").append(escape(String.valueOf(entry.get("kind")))).append("</font> ")
                            .append(escape(String.valueOf(entry.get("text")))).append("<br>");
                }
                html.append("</p>");
            }
            taskPause.setVisible(!state.equals("paused") && !state.equals("done") && !state.equals("dropped"));
            taskResume.setVisible(state.equals("paused"));
        }
        taskComment.setEnabled(task != null);
        taskReplan.setEnabled(task != null);
        if (task == null) {
            taskPause.setVisible(false);
            taskResume.setVisible(false);
        }
        details.setText(html.append("</body></html>").toString());
        details.setCaretPosition(0);
        detail = lines;
    }

    // ---- as tests read it

    /** The plan shown, how far it got, and its tasks depth first, each indented under its line of action. */
    List<String> lines() {
        var lines = new ArrayList<String>();
        lines.add(headingLine);
        var nodes = ((DefaultMutableTreeNode) model.getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof Line line) lines.add("  ".repeat(line.depth()) + line);
        }
        return lines;
    }

    /** The plans the chooser lists. */
    List<String> plansListed() {
        var listed = new ArrayList<String>();
        for (var i = 0; i < chooser.getItemCount(); i++) listed.add(chooser.getItemAt(i).toString());
        return listed;
    }

    /** The chosen task as the details say it, line by line. */
    List<String> detail() {
        return detail;
    }

    // ----

    private static javax.swing.Icon icon(String state) {
        return Icons.of(state.equals("dropped") ? "DISCARDED" : state.toUpperCase(Locale.ROOT));
    }

    private static String joined(Object list) {
        return String.join(", ", ((List<?>) list).stream().map(String::valueOf).toList());
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class LineRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
                                                      int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (((DefaultMutableTreeNode) value).getUserObject() instanceof Line line) {
                var finished = line.state().equals("done") || line.state().equals("dropped");
                var title = escape(line.title());
                if (line.state().equals("dropped")) title = "<s>" + title + "</s>";
                if (line.state().equals("doing")) title = "<b>" + title + "</b>";
                if (finished) title = "<font color='#808080'>" + title + "</font>";
                var extra = (line.of() > 0 ? "  " + line.done() + "/" + line.of() : "")
                        + (line.waiting().isEmpty() ? "" : "  waits " + String.join(", ", line.waiting()));
                setText("<html>" + title + "<font color='#808080'>" + escape(extra) + "  " + line.id() + "</font></html>");
                setIcon(icon(line.state()));
            }
            return this;
        }
    }
}
