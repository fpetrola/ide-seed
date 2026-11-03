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

        /** The plan seen again, step by step. */
        void replay(String plan);
    }

    /** A plan as the chooser lists it. */
    record Listed(String id, String title, String state, int done, int of) {
        @Override
        public String toString() {
            return title + " (" + state + ") " + done + "/" + of;
        }
    }

    /** A task as the tree shows it. */
    record Line(String id, String title, String state, int depth, int done, int of, List<String> waiting, String step) {
        @Override
        public String toString() {
            return id + " [" + state + "] " + title + (of > 0 ? " " + done + "/" + of : "")
                    + (waiting.isEmpty() ? "" : " (waits " + String.join(", ", waiting) + ")") + (step.isEmpty() ? "" : " - " + step);
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
    private final JEditorPane talk = new JEditorPane("text/html", "");
    private final javax.swing.JTabbedPane parts = new javax.swing.JTabbedPane();
    private List<String> talked = List.of();
    private final java.util.Set<Integer> openTools = new java.util.HashSet<>();
    private final java.util.Set<Integer> openSaid = new java.util.HashSet<>();
    private boolean allTalk;
    private final JButton planStart = button("Start", "start-plan", "DOING");
    private final JButton planPause = button("Pause", "pause-plan", "PAUSED");
    private final JButton planStop = button("Stop", "stop-plan", "BLOCKED");
    private final JButton planReplan = button("Replan\u2026", "replan-plan", null);
    private final JButton taskStart = button("Start", "start", "DOING");
    private final JButton taskPause = button("Pause", "pause", "PAUSED");
    private final JButton taskStop = button("Stop", "stop", "BLOCKED");
    private final JButton taskComment = button("Comment\u2026", "comment", null);
    private final JButton taskReplan = button("Replan\u2026", "replan", null);
    private final java.util.Set<Integer> expanded = new java.util.HashSet<>();
    private boolean allSteps;
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
        var planReplay = new JButton("Replay");
        planReplay.setToolTipText("The plan step by step as it was done: what each step takes, then what it changed");
        planReplay.putClientProperty("JButton.buttonType", "borderless");
        planReplay.addActionListener(event -> {
            if (!shown.isEmpty()) actions.replay(shown);
        });
        planBar.add(row(planStart, planPause, planStop, planReplan, planReplay), BorderLayout.CENTER);
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
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) click(event.getDescription());
        });
        var detailPanel = new JPanel(new BorderLayout());
        var detailScroll = new JScrollPane(details);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        talk.setEditable(false);
        talk.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        talk.addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED) click(event.getDescription());
        });
        var talkScroll = new JScrollPane(talk);
        talkScroll.setBorder(BorderFactory.createEmptyBorder());
        parts.addTab("Task", detailScroll);
        parts.addTab("Conversation", talkScroll);
        parts.setToolTipTextAt(1, "What you and Claude said, and the tools it used, while this task was being done");
        detailPanel.add(parts, BorderLayout.CENTER);
        detailPanel.add(row(taskStart, taskPause, taskStop, taskComment, taskReplan), BorderLayout.PAGE_END);

        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, detailPanel);
        split.setResizeWeight(0.35); // the details, and the conversation, take most of the height
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);
        showPlan(Map.of());
        showTask(null);
    }

    private JButton button(String text, String kind, String icon) {
        var button = new JButton(text);
        if (icon != null) button.setIcon(Icons.of(icon));
        button.setToolTipText(switch (kind.replace("-plan", "")) {
            case "start" -> "Claude works on it now";
            case "pause" -> "Claude finishes the step it is in, and waits";
            case "stop" -> "Claude stops at once (Esc in its terminal), and waits";
            case "comment" -> "Something for Claude to know about it";
            default -> "Ask Claude to change the plan";
        });
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
        var plans = (List<Map<String, Object>>) ide.call("plans", Map.of());
        var listed = plans.stream().map(p -> new Listed(String.valueOf(p.get("id")),
                String.valueOf(p.get("title")), String.valueOf(p.get("state")), number(p.get("done")), number(p.get("of")))).toList();
        // the plan that goes on, even paused or stopped; it is what the person wants to see
        var nowActive = plans.stream().filter(p -> Boolean.TRUE.equals(p.get("active"))).map(p -> String.valueOf(p.get("id")))
                .findFirst().orElse(listed.isEmpty() ? "" : listed.getLast().id());
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
    /** The plan shown, if any. */
    java.util.Optional<String> shownPlan() {
        return shown.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(shown);
    }

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
        if (!task.equals(chosenTask)) {
            expanded.clear();
            allSteps = false;
            openTools.clear();
            openSaid.clear();
            allTalk = false;
        }
        chosenTask = task;
        showTask(found);
        showTalk(task);
        filling = true;
        var root = (DefaultMutableTreeNode) model.getRoot();
        var nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof Line line && line.id().equals(task)) tree.setSelectionPath(new TreePath(node.getPath()));
        }
        filling = false;
    }

    /**
     * What a link of the details does: {@code task:t3} shows that task, {@code link:0} opens that code, {@code more:4}
     * shows all of that entry of the timeline, {@code steps} all the steps.
     */
    void click(String href) {
        var colon = href.indexOf(':');
        var what = colon < 0 ? href : href.substring(0, colon);
        var which = colon < 0 ? "" : href.substring(colon + 1);
        switch (what) {
            case "task" -> select(which);
            case "link" -> follow(Integer.parseInt(which));
            case "more" -> {
                expanded.add(Integer.parseInt(which));
                select(chosenTask);
            }
            case "steps" -> {
                allSteps = !allSteps;
                select(chosenTask);
            }
            case "tools" -> {
                openTools.add(Integer.parseInt(which));
                select(chosenTask);
            }
            case "said" -> {
                openSaid.add(Integer.parseInt(which));
                select(chosenTask);
            }
            case "earlier" -> {
                allTalk = true;
                select(chosenTask);
            }
            default -> { }
        }
    }

    /** Opens the code the chosen task's link {@code n} (from 0) is about. */
    void follow(int n) {
        if (n < 0 || n >= links.size()) return;
        var link = links.get(n);
        actions.open(String.valueOf(link.get("path")), number(link.get("line")), number(link.get("endLine")));
    }

    /**
     * What the person says: of the chosen task {@code start}, {@code pause}, {@code stop}, {@code resume},
     * {@code comment}, {@code replan}; of the plan shown the same, ending in {@code -plan}, or {@code switch}.
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
                    ((List<?>) task.get("waiting")).stream().map(String::valueOf).toList(), String.valueOf(task.getOrDefault("step", "")));
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
        var going = state.equals("active");
        planStart.setVisible(!named.isEmpty() && !going);
        planPause.setVisible(going);
        planStop.setVisible(going);
        planReplan.setVisible(!named.isEmpty());
    }

    private String headingLine = "No plan";

    private static final Map<String, String> COLORS = Map.of("doing", "#3F8BD8", "done", "#4FA85C", "blocked", "#D8586E",
            "paused", "#D6893C", "todo", "#8C8C8C", "dropped", "#8C8C8C");
    private static final Map<String, String> SIGNS = Map.of("doing", "\u25B6", "done", "\u2713", "blocked", "!", "paused", "\u2016",
            "todo", "\u25CB", "dropped", "\u00D7");
    private static final int SHORT = 160;
    private static final int STEPS = 5;

    /**
     * The chosen task, in parts one can take in at a glance: what it is and how it is, what it is doing now, what it is
     * about, its subtasks, its code, what was said of it (long notes cut, each to be opened), and its last steps.
     */
    @SuppressWarnings("unchecked")
    private void showTask(Map<String, Object> task) {
        links.clear();
        var lines = new ArrayList<String>();
        var html = new StringBuilder("<html><body style='margin:6px'>");
        if (task == null) {
            html.append("<font color='#808080'>Choose a task to see it.</font>");
        } else {
            var state = String.valueOf(task.get("state"));
            var color = COLORS.getOrDefault(state, "#8C8C8C");
            var parent = String.valueOf(task.get("parent"));
            lines.add(task.get("id") + " " + task.get("title"));
            lines.add("state: " + state);
            lines.add("plan: " + task.get("plan") + (parent.isEmpty() ? "" : ", part of " + parent));
            html.append("<div style='font-size:115%'><b>").append(escape(String.valueOf(task.get("title")))).append("</b></div>");
            var where = new ArrayList<String>();
            where.add(String.valueOf(task.get("id")));
            if (!parent.isEmpty()) where.add("in <a href='task:" + parent + "'>" + parent + "</a>");
            where.add("plan " + task.get("plan"));
            html.append("<div><font color='").append(color).append("'><b>").append(SIGNS.getOrDefault(state, "")).append(' ').append(state)
                    .append("</b></font> &nbsp;<font color='#808080'>").append(String.join(" \u00B7 ", where)).append("</font></div>");
            var waiting = (List<?>) task.get("waiting");
            if (!waiting.isEmpty()) {
                lines.add("waits for: " + joined(waiting));
                html.append("<div><font color='#D6893C'>waits for</font> ").append(String.join(", ", waiting.stream()
                        .map(id -> "<a href='task:" + id + "'>" + id + "</a>").toList())).append("</div>");
            }
            var timeline = (List<Map<String, Object>>) task.get("timeline");
            var steps = new ArrayList<Integer>();
            var said = new ArrayList<Integer>();
            for (var i = 0; i < timeline.size(); i++) (timeline.get(i).get("kind").equals("step") ? steps : said).add(i);
            if (state.equals("doing") && !steps.isEmpty()) {
                var now = String.valueOf(timeline.get(steps.getLast()).get("text"));
                lines.add("now: " + now);
                html.append("<div style='margin-top:4px'><font color='#6A9FD8'>\u25B8 now: ").append(escape(shortened(now, SHORT)))
                        .append("</font></div>");
            }
            var description = String.valueOf(task.get("description"));
            if (!description.isEmpty()) {
                lines.add(description);
                html.append(section("About")).append("<div>").append(escape(description).replace("\n", "<br>")).append("</div>");
            }
            var subtasks = (List<?>) task.get("subtasks");
            if (!subtasks.isEmpty()) {
                html.append(section("Subtasks"));
                var plan = (Map<String, Object>) ide.call("plan", Map.of("plan", String.valueOf(task.get("plan"))));
                for (var line : (List<Map<String, Object>>) plan.get("tasks")) {
                    if (!subtasks.contains(line.get("id"))) continue;
                    var its = String.valueOf(line.get("state"));
                    lines.add("subtask " + line.get("id") + " [" + its + "] " + line.get("title"));
                    html.append("<div><font color='").append(COLORS.getOrDefault(its, "#8C8C8C")).append("'>").append(SIGNS.getOrDefault(its, ""))
                            .append("</font> <a href='task:").append(line.get("id")).append("'>").append(escape(String.valueOf(line.get("title"))))
                            .append("</a> <font color='#808080'>").append(line.get("id")).append("</font></div>");
                }
            }
            var linked = (List<Map<String, Object>>) task.get("links");
            if (!linked.isEmpty()) {
                html.append(section("Code"));
                for (var link : linked) {
                    var place = link.get("path") + ":" + link.get("line") + (number(link.get("endLine")) > number(link.get("line"))
                            ? "-" + link.get("endLine") : "");
                    var name = String.valueOf(link.get("element")).isEmpty() ? place : String.valueOf(link.get("element"));
                    var note = String.valueOf(link.get("note"));
                    lines.add("link " + links.size() + ": " + name + " " + place + (note.isEmpty() ? "" : " - " + note));
                    html.append("<div><a href='link:").append(links.size()).append("'>").append(escape(name)).append("</a> <font color='#808080'>")
                            .append(escape(place)).append(note.isEmpty() ? "" : " \u2014 " + escape(note)).append("</font></div>");
                    links.add(link);
                }
            }
            if (!said.isEmpty()) {
                html.append(section("Notes"));
                for (var i : said.reversed()) {
                    var entry = timeline.get(i);
                    var kind = String.valueOf(entry.get("kind"));
                    var text = String.valueOf(entry.get("text"));
                    lines.add(time(entry) + " " + entry.get("who") + " " + kind + ": " + text);
                    html.append("<div style='margin-top:3px'><font color='#808080'>").append(time(entry)).append("</font> <b>")
                            .append(entry.get("who").equals("person") ? "you" : "Claude").append("</b> ");
                    if (kind.equals("state")) {
                        html.append("<font color='").append(COLORS.getOrDefault(text, "#808080")).append("'>\u2192 ").append(escape(text)).append("</font>");
                    } else {
                        if (!kind.equals("note")) html.append("<font color='#808080'>").append(escape(kind)).append("</font> ");
                        var whole = expanded.contains(i) || text.length() <= SHORT;
                        html.append(escape(whole ? text : shortened(text, SHORT)).replace("\n", "<br>"));
                        if (!whole) html.append(" <a href='more:").append(i).append("'>more</a>");
                    }
                    html.append("</div>");
                }
            }
            if (!steps.isEmpty()) {
                html.append(section("Steps"));
                var newest = steps.reversed();
                for (var i : allSteps ? newest : newest.subList(0, Math.min(STEPS, newest.size()))) {
                    var entry = timeline.get(i);
                    lines.add(time(entry) + " " + entry.get("who") + " step: " + entry.get("text"));
                    html.append("<div><font color='#808080'>").append(time(entry)).append("</font> ")
                            .append(escape(shortened(String.valueOf(entry.get("text")), SHORT))).append("</div>");
                }
                if (steps.size() > STEPS) {
                    html.append("<div><a href='steps'>").append(allSteps ? "only the last " + STEPS : (steps.size() - STEPS) + " earlier steps")
                            .append("</a></div>");
                }
            }
            var finished = state.equals("done") || state.equals("dropped");
            taskStart.setVisible(!finished && !state.equals("doing"));
            taskPause.setVisible(state.equals("doing"));
            taskStop.setVisible(state.equals("doing"));
        }
        taskComment.setEnabled(task != null);
        taskReplan.setEnabled(task != null);
        if (task == null) {
            taskStart.setVisible(false);
            taskPause.setVisible(false);
            taskStop.setVisible(false);
        }
        details.setText(html.append("</body></html>").toString());
        details.setCaretPosition(0);
        detail = lines;
    }

    private static final int TALK = 40;
    private static final int LONG_SAID = 600;

    /**
     * What was said while the task was being done, in order: the person's messages marked, the agent's answers, and its
     * tools, those used one after another as one line until opened; the last ones, until the earlier are asked for.
     */
    @SuppressWarnings("unchecked")
    private void showTalk(String task) {
        List<Map<String, Object>> messages;
        try {
            messages = (List<Map<String, Object>>) ((Map<String, Object>) ide.call("task-talk", Map.of("task", task))).get("messages");
        } catch (RuntimeException none) {
            messages = List.of();
        }
        var lines = new ArrayList<String>();
        var html = new StringBuilder("<html><body style='margin:6px'>");
        if (messages.isEmpty()) {
            html.append("<font color='#808080'>Nothing was said while this task was being done, or Claude's conversation is not known yet.</font>");
        }
        var from = allTalk ? 0 : Math.max(0, messages.size() - TALK);
        if (from > 0) html.append("<div><a href='earlier'>").append(from).append(" earlier</a></div>");
        for (var i = from; i < messages.size(); i++) {
            var message = messages.get(i);
            var text = String.valueOf(message.get("text"));
            var time = time(message);
            if (message.get("kind").equals("tool")) {
                var group = i;
                var tools = new ArrayList<String>();
                while (i < messages.size() && messages.get(i).get("kind").equals("tool")) tools.add(String.valueOf(messages.get(i++).get("text")));
                i--;
                tools.forEach(tool -> lines.add(time + " agent tool: " + tool));
                html.append("<div style='margin-top:2px'><font color='#808080'>").append(time).append(" \u25B8 ");
                if (tools.size() == 1 || openTools.contains(group)) {
                    html.append(String.join("<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;\u25B8 ", tools.stream().map(t -> escape(shortened(t, 100))).toList()));
                } else {
                    html.append(escape(shortened(tools.getFirst(), 60))).append(" <a href='tools:").append(group).append("'>+")
                            .append(tools.size() - 1).append(" tools</a>");
                }
                html.append("</font></div>");
                continue;
            }
            var person = message.get("who").equals("person");
            lines.add(time + " " + message.get("who") + " text: " + text);
            var whole = openSaid.contains(i) || text.length() <= LONG_SAID;
            var said = marked(escape(whole ? text : shortened(text, LONG_SAID)));
            html.append("<table width='100%' cellpadding='4' cellspacing='0' style='margin-top:4px'")
                    .append(person ? " bgcolor='#3A4A5A'" : "").append("><tr><td><font color='#808080'>").append(time)
                    .append("</font> <b>").append(person ? "you" : "Claude").append("</b><br>").append(said);
            if (!whole) html.append(" <a href='said:").append(i).append("'>more</a>");
            html.append("</td></tr></table>");
        }
        talk.setText(html.append("</body></html>").toString());
        talk.setCaretPosition(talk.getDocument().getLength()); // the latest, as a chat shows it
        parts.setTitleAt(1, messages.isEmpty() ? "Conversation" : "Conversation (" + messages.size() + ")");
        talked = lines;
    }

    /** Claude's markdown, as far as it reads better: bold, code, list items, lines. */
    private static String marked(String escaped) {
        return escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>")
                .replaceAll("`([^`]+)`", "<font face='monospaced' color='#A9B7C6'>$1</font>")
                .replaceAll("(?m)^\\s*[-*] ", "\u2022 ")
                .replace("\n", "<br>");
    }

    private static String section(String name) {
        return "<div style='margin-top:8px'><font color='#808080' size='-1'><b>" + name.toUpperCase(Locale.ROOT) + "</b></font></div>";
    }

    private static String time(Map<String, Object> entry) {
        var at = String.valueOf(entry.get("at"));
        return at.length() >= 16 ? at.substring(11, 16) : at;
    }

    private static String shortened(String text, int longest) {
        var line = text.strip();
        return line.length() <= longest ? line : line.substring(0, longest - 1) + "\u2026";
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

    /** The details as they are drawn. */
    String html() {
        return details.getText();
    }

    /** What was said while the chosen task was being done, line by line. */
    List<String> conversation() {
        return talked;
    }

    /** The conversation as it is drawn. */
    String conversationHtml() {
        return talk.getText();
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
                var step = line.step().isEmpty() ? "" : "<br><font color='#6A9FD8'>\u25B8 " + escape(shortened(line.step(), 60)) + "</font>";
                setText("<html>" + title + "<font color='#808080'>" + escape(extra) + "  " + line.id() + "</font>" + step + "</html>");
                setIcon(icon(line.state()));
            }
            return this;
        }
    }
}
