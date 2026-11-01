package ide.ui;

import ide.core.Capability;

import javax.swing.AbstractAction;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.BorderFactory;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The window's content: the project's files on the left, the open files in the middle, what the IDE says below. Each
 * action is a key of {@link Keymap}; it calls capabilities and shows what they answer, and asks people through
 * {@link Dialogs} what a capability takes.
 */
final class Workbench extends JPanel {

    /** A file in the project's tree, with how many problems it has. */
    private record File(String path, long problems) {
        @Override
        public String toString() {
            return path.substring(path.lastIndexOf('/') + 1, path.length() - (path.endsWith(".java") ? 5 : 0));
        }
    }

    /** A package or a file, with its icon; a file with problems in red, with how many. */
    private static final class FileRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            var object = ((DefaultMutableTreeNode) value).getUserObject();
            if (object instanceof File file) {
                var text = file.problems() == 0 ? file.toString()
                        : "<html><font color='#E05555'><u>" + file + "</u></font>&nbsp;&nbsp;<font color='#808080'>" + file.problems() + "</font></html>";
                super.getTreeCellRendererComponent(tree, text, selected, expanded, leaf, row, focus);
                setIcon(Icons.of("CLASS"));
            } else {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                setIcon(Icons.of("PACKAGE"));
            }
            return this;
        }
    }

    private final Ide ide;
    private final Dialogs dialogs;
    private final DefaultTreeModel tree = new DefaultTreeModel(new DefaultMutableTreeNode("project"));
    private final JTree project = new JTree(tree);
    private final Editors editors;
    private final Views views = new Views(this::openProblem, this::openResult);
    private final JLabel status = new JLabel(" ");
    private final JLabel position = new JLabel(" ");
    private final JLabel libraries = new JLabel(" ");
    private final Deque<String> back = new ArrayDeque<>();
    private final Deque<String> forward = new ArrayDeque<>();
    private List<String> files = List.of();
    private List<Map<String, Object>> problems = List.of();

    Workbench(Ide ide, Dialogs dialogs) {
        super(new BorderLayout());
        this.ide = ide;
        this.dialogs = dialogs;
        this.editors = new Editors(Keymap.KEYS.stream().map(Keymap.Key::stroke).toList(), new Editors.Help() {
            @Override
            public List<Map<String, Object>> completions(String path, String text, int offset) {
                return ide.records("complete", Map.of("path", path, "text", text, "offset", String.valueOf(offset)));
            }

            @Override
            public Optional<Runnable> link(String path, int line, int column) {
                if (!editors().unapplied().isEmpty()) return Optional.empty();
                var at = ide.record("at", Map.of("path", path, "line", String.valueOf(line), "column", String.valueOf(column)));
                return at == null ? Optional.empty() : Optional.of(() -> go(String.valueOf(at.get("element"))));
            }

            @Override
            public void moved() {
                position.setText(editors().current().map(path -> editors().line() + ":" + editors().column()).orElse(" "));
            }
        });
        var center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editors, views);
        center.setResizeWeight(0.72);
        center.setBorder(BorderFactory.createEmptyBorder());
        var projectScroll = new JScrollPane(project);
        projectScroll.setBorder(BorderFactory.createEmptyBorder());
        var main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectScroll, center);
        main.setDividerLocation(260);
        main.setBorder(BorderFactory.createEmptyBorder());
        add(main, BorderLayout.CENTER);
        var bar = new JPanel(new BorderLayout(16, 0));
        bar.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        bar.add(status, BorderLayout.CENTER);
        var right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 16, 0));
        right.setOpaque(false);
        right.add(libraries);
        right.add(position);
        bar.add(right, BorderLayout.LINE_END);
        add(bar, BorderLayout.PAGE_END);
        project.setRootVisible(false);
        project.setShowsRootHandles(true);
        project.setCellRenderer(new FileRenderer());
        project.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                var path = project.getPathForLocation(event.getX(), event.getY());
                if (event.getClickCount() == 2 && path != null
                        && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof File file) {
                    remember();
                    open(file.path(), 1, 1);
                }
            }
        });
        for (var key : Keymap.KEYS) {
            getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(key.stroke(), key.action());
            getActionMap().put(key.action(), new AbstractAction(key.description()) {
                @Override
                public void actionPerformed(ActionEvent event) {
                    act(key.action());
                }
            });
        }
        refresh();
        var classpath = ide.record("classpath", Map.of());
        var jars = strings(classpath.get("entries"));
        libraries.setText(jars.isEmpty() ? "JDK only" : jars.size() + " libraries");
        libraries.setToolTipText(classpath.get("problem") == null ? String.join("\n", jars) : String.valueOf(classpath.get("problem")));
        say("libraries: " + (jars.isEmpty() ? "none" : jars.size() + " from Maven")
                + (classpath.get("problem") == null ? "" : " (" + String.valueOf(classpath.get("problem")).lines().findFirst().orElse("") + ")"));
    }

    /** Does what a key of the keymap does; what goes wrong is said, not thrown. */
    void act(String action) {
        try {
            switch (action) {
                case "apply-edit" -> applyEdits();
                case "save" -> {
                    applyEdits();
                    ide.call("save", Map.of());
                    say("saved");
                    refresh();
                }
                case "undo-change" -> {
                    ide.call("undo", Map.of());
                    say("undone");
                    refresh();
                }
                case "quick-fix" -> quickFix();
                case "complete" -> editors.complete();
                case "rename" -> rename();
                case "change-signature", "move" -> refactor(action);
                case "refactor" -> {
                    var selection = selection();
                    var offered = ide.list("library", Map.of()).stream().map(line -> line.substring(line.indexOf(' ') + 1))
                            .filter(id -> selection.isEmpty() || takes(ide.capability(id), selection.get()))
                            .map(id -> new Dialogs.Option(id, "REFACTORING", ide.capability(id).description())).toList();
                    dialogs.choose("Refactor " + selection.map(Form.Selection::element).orElse(""), offered, editors.caretOnScreen())
                            .ifPresent(id -> {
                                if (id.equals("rename")) rename();
                                else refactor(id);
                            });
                }
                case "open-declaration" -> selection().ifPresent(selected -> go(selected.element()));
                case "hierarchy" -> taken("hierarchy").ifPresent(type ->
                        results("Hierarchy of " + type, ide.list("hierarchy", Map.of("type", type))));
                case "callers" -> taken("callers").ifPresent(method ->
                        results("Callers of " + method, ide.list("callers", Map.of("method", method))));
                case "references" -> taken("references").ifPresent(element ->
                        results("References to " + element, ide.list("references", Map.of("element", element))));
                case "open-type" -> dialogs.choose("Enter class name", options(Map.of("kinds", "CLASS,INTERFACE,ENUM")), null)
                        .ifPresent(this::go);
                case "outline" -> editors.current().ifPresent(path ->
                        dialogs.choose(path.substring(path.lastIndexOf('/') + 1), options(Map.of("path", path)), null).ifPresent(this::go));
                case "go-to-line" -> dialogs.line("Go to line", String.valueOf(editors.line()), editors.caretOnScreen())
                        .ifPresent(line -> editors.caret(Integer.parseInt(line.strip()), 1));
                case "next-problem" -> problem(1);
                case "previous-problem" -> problem(-1);
                case "back" -> travel(back, forward);
                case "forward" -> travel(forward, back);
                case "zoom-in" -> editors.zoom(1);
                case "zoom-out" -> editors.zoom(-1);
                case "keys" -> dialogs.tell("Keys", Keymap.KEYS.stream()
                        .map(key -> String.format("%-16s %s", key.stroke().toString().replace("pressed ", ""), key.description())).toList(), null);
                default -> throw new IllegalArgumentException("no action " + action);
            }
        } catch (RuntimeException failed) {
            say("error: " + failed.getMessage());
        }
    }

    private Editors editors() {
        return editors;
    }

    /** The elements the capability answers, as options with the icon of their kind. */
    private List<Dialogs.Option> options(Map<String, String> args) {
        return ide.records("elements", args).stream()
                .map(e -> Dialogs.Option.of(String.valueOf(e.get("element")), String.valueOf(e.get("kind")))).toList();
    }

    /** Opens a file at a line and a column, from 1. */
    void open(String path, int line, int column) {
        editors.open(path, ide.text("show", Map.of("path", path)));
        editors.caret(line, column);
    }

    /** Everything shown again as the program is now: files, problems, editors, changes not saved. */
    void refresh() {
        files = ide.list("ls", Map.of());
        problems = ide.records("diagnostics", Map.of());
        var root = new DefaultMutableTreeNode("project");
        var folders = new LinkedHashMap<String, DefaultMutableTreeNode>();
        for (var path : files) {
            var slash = path.lastIndexOf('/');
            var folder = slash < 0 ? root : folders.computeIfAbsent(path.substring(0, slash), name -> {
                var node = new DefaultMutableTreeNode(name.replace('/', '.'));
                root.add(node);
                return node;
            });
            folder.add(new DefaultMutableTreeNode(new File(path, problems.stream().filter(p -> path.equals(p.get("path"))).count())));
        }
        tree.setRoot(root);
        for (var row = 0; row < project.getRowCount(); row++) project.expandRow(row);
        for (var path : editors.open()) if (files.contains(path)) editors.applied(path, ide.text("show", Map.of("path", path)));
        editors.problems(problems);
        views.problems(problems.stream().map(p -> p.get("path") + ":" + p.get("line") + ": "
                + String.valueOf(p.get("message")).lines().findFirst().orElse("")).toList());
        views.changes(ide.text("diff", Map.of()));
    }

    /** The menus: each action with its key. */
    JMenuBar menus() {
        var bar = new JMenuBar();
        var file = new JMenu("File");
        var openFolder = new JMenuItem("Open Folder...");
        openFolder.addActionListener(event -> opener.accept(null));
        file.add(openFolder);
        file.addSeparator();
        bar.add(file);
        for (var action : List.of("apply-edit", "save", "undo-change")) file.add(item(action));
        menu(bar, "Edit", "complete", "quick-fix");
        menu(bar, "Refactor", "rename", "refactor", "change-signature", "move");
        menu(bar, "Navigate", "open-declaration", "open-type", "outline", "references", "callers", "hierarchy", "go-to-line",
                "next-problem", "previous-problem", "back", "forward");
        menu(bar, "View", "zoom-in", "zoom-out", "keys");
        return bar;
    }

    List<String> files() {
        return files;
    }

    String text(String path) {
        return editors.text(path);
    }

    /** Writes in an editor as people would, leaving the caret where it was; nothing is applied yet. */
    void type(String path, String text) {
        var line = editors.line();
        var column = editors.column();
        editors.type(path, text);
        editors.caret(line, column);
    }

    Views views() {
        return views;
    }

    /** Where the caret is: {@code path:line:column}. */
    String where() {
        return editors.current().map(path -> path + ":" + editors.line() + ":" + editors.column()).orElse("");
    }

    private void menu(JMenuBar bar, String name, String... actions) {
        var menu = new JMenu(name);
        for (var action : actions) menu.add(item(action));
        bar.add(menu);
    }

    private JMenuItem item(String action) {
        var item = new JMenuItem(getActionMap().get(action));
        item.setAccelerator(Keymap.KEYS.stream().filter(key -> key.action().equals(action)).findFirst().orElseThrow().stroke());
        return item;
    }

    private java.util.function.Consumer<java.nio.file.Path> opener = folder -> { };

    /** What File, Open Folder does: the window opens another project. */
    void opener(java.util.function.Consumer<java.nio.file.Path> opener) {
        this.opener = opener;
    }

    /** Applies what the editors say and the program does not yet: an edit by hand each, shown as it went. */
    private void applyEdits() {
        var unapplied = editors.unapplied();
        if (unapplied.isEmpty()) return;
        unapplied.forEach((path, text) -> {
            shown("edit " + path, ide.record("edit", Map.of("path", path, "text", text)));
            editors.applied(path, text);
        });
        refresh();
    }

    /** A refactoring's form, preloaded with the element under the caret, and the refactoring with what it is given. */
    private void refactor(String capability) {
        var form = Form.of(ide.capability(capability), ide, selection());
        dialogs.fill(form.title(), form, values -> preview(capability, form.args(values)))
                .ifPresent(values -> change(capability, form.args(values)));
    }

    /** What a refactoring would change: its diff, or why it would not apply. */
    private String preview(String capability, Map<String, Object> args) {
        var report = ide.record("preview", Map.of("refactoring", capability, "args", args));
        return Boolean.TRUE.equals(report.get("applied")) ? String.valueOf(report.get("diff"))
                : String.join("\n", strings(report.get("violations")));
    }

    private void quickFix() {
        var path = editors.current().orElseThrow(() -> new IllegalStateException("no file is open"));
        var line = String.valueOf(editors.line());
        applyEdits();
        var titles = ide.list("fixes", Map.of("path", path, "line", line));
        if (titles.isEmpty()) {
            say("no quick fix for " + path + ":" + line);
            return;
        }
        var at = editors.caretOnScreen();
        dialogs.choose("Quick fix", titles.stream().map(title -> Dialogs.Option.of(title, "FIX")).toList(), at).ifPresent(title -> {
            var answers = new java.util.LinkedHashMap<String, Object>();
            for (var ask : ide.records("asks", Map.of("path", path, "line", line, "title", title))) {
                var answer = dialogs.line(String.valueOf(ask.get("question")), String.valueOf(ask.get("default")), at);
                if (answer.isEmpty()) return;
                answers.put(String.valueOf(ask.get("name")), answer.get());
            }
            change("fix", Map.of("path", path, "line", line, "title", title, "answers", answers));
        });
    }

    /** A new name for the element under the caret, written where it is, as IDEs rename in place. */
    private void rename() {
        var selection = selection();
        if (selection.isEmpty()) {
            say("rename: the caret is on no element");
            return;
        }
        dialogs.line("Rename " + selection.get().element(), editors.name(), editors.caretOnScreen())
                .ifPresent(name -> change("rename", Map.of("element", selection.get().element(), "name", name)));
    }

    /** Runs a change after applying what the editors say, and shows what it did. */
    private void change(String capability, Map<String, ?> args) {
        applyEdits();
        shown(capability, ide.record(capability, args));
        refresh();
    }

    private void shown(String what, Map<String, Object> report) {
        views.steps(strings(report.get("steps")));
        say(what + (Boolean.TRUE.equals(report.get("applied")) ? ": applied, " + report.get("guarantee")
                : ": not applied: " + String.join("; ", strings(report.get("violations")))));
    }

    /** The element under the caret, with its kind; what the editors say is applied first, so that it is the program's. */
    private Optional<Form.Selection> selection() {
        applyEdits();
        var path = editors.current();
        if (path.isEmpty()) return Optional.empty();
        var at = ide.record("at", Map.of("path", path.get(), "line", String.valueOf(editors.line()), "column", String.valueOf(editors.column())));
        return at == null ? Optional.empty() : Optional.of(new Form.Selection(String.valueOf(at.get("element")), String.valueOf(at.get("kind"))));
    }

    /** The element under the caret, if a capability takes it as its element; else what it takes is said. */
    private Optional<String> taken(String capability) {
        var selection = selection();
        if (selection.isPresent() && takes(ide.capability(capability), selection.get())) return Optional.of(selection.get().element());
        var kinds = ide.capability(capability).params().getFirst().kinds();
        say(capability + (kinds.isEmpty() ? "" : " takes a " + String.join(" or ", kinds)) + ": the caret is on "
                + selection.map(s -> "a " + s.kind()).orElse("no element"));
        return Optional.empty();
    }

    /** Whether a capability's first element parameter takes the selection. */
    private static boolean takes(Capability capability, Form.Selection selection) {
        return capability.params().stream().filter(p -> p.type().startsWith("element")).findFirst().filter(selection::fits).isPresent();
    }

    /** Opens where an element is declared, remembering where the caret was. */
    private void go(String element) {
        var where = ide.record("where", Map.of("element", element));
        if (where == null) {
            say(element + " is nowhere in the sources");
            return;
        }
        remember();
        open(String.valueOf(where.get("path")), ((Number) where.get("line")).intValue(), ((Number) where.get("column")).intValue());
    }

    private void results(String title, List<String> lines) {
        views.results(title, lines);
        say(title + ": " + lines.size());
    }

    private void openProblem(String problem) {
        var parts = problem.split(":", 3);
        remember();
        open(parts[0], Integer.parseInt(parts[1]), 1);
    }

    /** A result names an element, maybe indented or marked as the focus ({@code > }). */
    private void openResult(String result) {
        go(result.strip().replaceFirst("^> ", ""));
    }

    private void problem(int direction) {
        var path = editors.current().orElseThrow(() -> new IllegalStateException("no file is open"));
        var lines = problems.stream().filter(p -> path.equals(p.get("path"))).map(p -> ((Number) p.get("line")).intValue())
                .distinct().sorted().toList();
        if (lines.isEmpty()) {
            say("no problems in " + path);
            return;
        }
        var line = editors.line();
        var next = direction > 0 ? lines.stream().filter(l -> l > line).findFirst().orElse(lines.getFirst())
                : lines.reversed().stream().filter(l -> l < line).findFirst().orElse(lines.getLast());
        editors.caret(next, 1);
    }

    private void remember() {
        var here = where();
        if (!here.isEmpty()) back.push(here);
        forward.clear();
    }

    private void travel(Deque<String> from, Deque<String> to) {
        if (from.isEmpty()) return;
        var here = where();
        if (!here.isEmpty()) to.push(here);
        var there = from.pop().split(":");
        open(there[0], Integer.parseInt(there[1]), Integer.parseInt(there[2]));
    }

    private void say(String line) {
        views.say(line);
        status.setText(line);
    }



    private static List<String> strings(Object list) {
        return list instanceof List<?> items ? items.stream().map(String::valueOf).toList() : List.of();
    }
}
