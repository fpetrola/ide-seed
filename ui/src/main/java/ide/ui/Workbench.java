package ide.ui;

import ide.core.Json;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
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

    /**
     * A file or a package in the project's tree, with the worst of what is wrong in it (ERROR, WARNING, or null), as
     * Eclipse marks it.
     */
    record Node(String path, String name, boolean file, String severity) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** A package or a file, with its icon, marked at its bottom left with what is wrong in it. */
    private static final class FileRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            if (((DefaultMutableTreeNode) value).getUserObject() instanceof Node node) {
                setIcon(Icons.marked(Icons.of(node.file() ? "CLASS" : "PACKAGE"), node.severity()));
                setToolTipText(node.severity() == null ? null : node.path() + (node.severity().equals("ERROR") ? " has errors" : " has warnings"));
            }
            return this;
        }
    }

    /** The worst of the problems' severities: ERROR over WARNING over none. */
    private static String worst(java.util.stream.Stream<Map<String, Object>> problems) {
        var severities = problems.map(p -> String.valueOf(p.get("severity"))).collect(java.util.stream.Collectors.toSet());
        return severities.contains("ERROR") ? "ERROR" : severities.contains("WARNING") ? "WARNING" : null;
    }

    private final Ide ide;
    private final Dialogs dialogs;
    private final DefaultTreeModel tree = new DefaultTreeModel(new DefaultMutableTreeNode("project"));
    private final JTree project = new JTree(tree);
    private final Editors editors;
    private final Views views = new Views(this::openProblem, this::openResult);
    private final PlansView plans;
    /** The terminal, in the project's folder; none if the IDE was not opened on a folder. */
    private final Terminal terminal;
    private final JLabel status = new JLabel(" ");
    private final JLabel position = new JLabel(" ");
    private final JLabel libraries = new JLabel(" ");
    private final Deque<String> back = new ArrayDeque<>();
    /** The bulb is shown again a moment after the caret stops, not at every step. */
    private final javax.swing.Timer bulbs = new javax.swing.Timer(250, event -> bulb());
    /** What is typed is compiled a moment after typing stops, as Eclipse's reconciler does, without applying it. */
    private final javax.swing.Timer checks = new javax.swing.Timer(500, event -> check());
    /** The selections an extended selection came from, to shrink back to. */
    private final Deque<int[]> widened = new ArrayDeque<>();
    /** How many occurrences the last rename marked, for the tests to see. */
    int renaming;
    private final Deque<String> forward = new ArrayDeque<>();
    private List<String> files = List.of();
    private List<Map<String, Object>> problems = List.of();

    Workbench(Ide ide, Dialogs dialogs) {
        super(new BorderLayout());
        this.ide = ide;
        this.dialogs = dialogs;
        this.terminal = ide.folder().map(Terminal::new).orElse(null);
        this.plans = new PlansView(ide, new PlansView.Actions() {
            @Override
            public void steer(String kind, String plan, String task, String text) {
                Workbench.this.steer(kind, plan, task, text);
            }

            @Override
            public void open(String path, int line, int endLine) {
                Workbench.this.show(path, line, endLine);
            }

            @Override
            public java.util.Optional<String> ask(String title) {
                return dialogs.line(title, "", Workbench.this.plansOnScreen());
            }
        });
        views.addTab("Run", Icons.of("RUN"), console);
        if (terminal != null) views.addTab("Terminal", Icons.of("TERMINAL"), terminal);
        this.editors = new Editors(Keymap.strokes(), new Editors.Help() {
            @Override
            public List<Map<String, Object>> completions(String path, String text, int offset) {
                return ide.records("complete", Map.of("path", path, "text", text, "offset", String.valueOf(offset)));
            }

            @Override
            public void undo() {
                act("undo-change");
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
                bulbs.restart();
            }

            @Override
            public void typed(String path) {
                checks.restart();
            }

            @Override
            public void bulb() {
                act("context-actions");
            }

            @Override
            public void run(String path) {
                Workbench.this.run(path);
            }

            @Override
            public javax.swing.JPopupMenu menu() {
                return contextMenu();
            }

            @Override
            public void close(String path) {
                Workbench.this.close(path);
            }
        });
        bulbs.setRepeats(false);
        checks.setRepeats(false);
        var center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editors, views);
        center.setResizeWeight(0.85);
        center.setBorder(BorderFactory.createEmptyBorder());
        var projectScroll = new JScrollPane(project);
        projectScroll.setBorder(BorderFactory.createEmptyBorder());
        var withPlans = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, plans); // the plans beside the editors, seen with the terminal
        withPlans.setResizeWeight(1);
        withPlans.setBorder(BorderFactory.createEmptyBorder());
        plans.setPreferredSize(new java.awt.Dimension(Math.round(Zoom.of(340)), 0));
        var main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectScroll, withPlans);
        main.setDividerLocation(260);
        main.setBorder(BorderFactory.createEmptyBorder());
        add(main, BorderLayout.CENTER);
        add(runBar(), BorderLayout.PAGE_START);
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
        javax.swing.ToolTipManager.sharedInstance().registerComponent(project);
        project.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                var path = project.getPathForLocation(event.getX(), event.getY());
                if (event.getClickCount() == 2 && path != null
                        && ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof Node file && file.file()) {
                    remember();
                    open(file.path(), 1, 1);
                }
            }
        });
        for (var key : Keymap.KEYS) {
            key.strokes().forEach(stroke -> getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(stroke, key.action()));
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
                    if (!applyEdits()) return;
                    ide.call("save", Map.of());
                    say("saved");
                    refresh();
                }
                case "undo-change" -> {
                    ide.call("undo", Map.of());
                    say("undone");
                    refresh();
                }
                case "context-actions" -> contextActions();
                case "close-editor" -> editors.current().ifPresent(this::close);
                case "complete" -> editors.complete();
                case "rename" -> rename();
                case "change-signature", "move", "extract-method", "introduce-constant", "introduce-field", "safe-delete" -> refactor(action);
                case "inline" -> refactor(selection().filter(s -> takes(ide.capability("inline-variable"), s)).isPresent()
                        ? "inline-variable" : "inline-method"); // one key, as in IntelliJ: a local under the caret, or a method
                case "refactor" -> {
                    var offered = refactorings(candidates()).stream()
                            .map(id -> new Dialogs.Option(label(id), "REFACTORING", ide.capability(id).description())).toList();
                    dialogs.choose("Refactor This", offered, editors.caretOnScreen()).ifPresent(this::refactorLabelled);
                }
                case "generate" -> {
                    var offered = ide.list("library", Map.of()).stream().map(line -> line.substring(line.indexOf(' ') + 1))
                            .filter(id -> id.startsWith("generate-") || id.startsWith("encapsulate-") || id.equals("introduce-factory"))
                            .map(id -> new Dialogs.Option(label(id), "REFACTORING", ide.capability(id).description())).toList();
                    dialogs.choose("Generate", offered, editors.caretOnScreen()).ifPresent(this::refactorLabelled);
                }
                case "extend-selection" -> extendSelection();
                case "shrink-selection" -> {
                    if (!widened.isEmpty()) editors.select(widened.pop());
                }
                case "find-action" -> {
                    var offered = new java.util.ArrayList<Dialogs.Option>();
                    Keymap.KEYS.forEach(key -> offered.add(new Dialogs.Option(key.description(), "STEP",
                            key.stroke().toString().replace("pressed ", ""))));
                    ide.list("library", Map.of()).stream().map(line -> line.substring(line.indexOf(' ') + 1))
                            .forEach(id -> offered.add(new Dialogs.Option(label(id), "REFACTORING", ide.capability(id).description())));
                    dialogs.choose("Find Action", offered, null).ifPresent(chosen -> Keymap.KEYS.stream()
                            .filter(key -> key.description().equals(chosen)).findFirst()
                            .ifPresentOrElse(key -> act(key.action()), () -> refactorLabelled(chosen)));
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
                case "terminal" -> terminal();
                case "run" -> editors.current().ifPresentOrElse(this::run, () -> say("run: no file is open"));
                case "rerun" -> {
                    if (console.title().isEmpty()) {
                        act("run");
                    } else {
                        saved();
                        console.rerun();
                        views.setSelectedComponent(console);
                    }
                }
                case "stop-run" -> console.stop();
                case "run-chosen" -> {
                    if (configs.getSelectedItem() instanceof String chosen && !chosen.equals(EDIT)) runConfig(chosen);
                    else act("run");
                }
                case "edit-configs" -> editConfigs();
                case "claude" -> claude(true);
                case "claude-new" -> claude(false);
                case "zoom-in" -> zoom(1);
                case "zoom-out" -> zoom(-1);
                case "zoom-reset" -> zoom(0);
                case "keys" -> dialogs.tell("Keys", Keymap.KEYS.stream()
                        .map(key -> String.format("%-30s %s", String.join(", ", key.strokes().stream()
                                .map(stroke -> stroke.toString().replace("pressed ", "")).toList()), key.description())).toList(), null);
                default -> {
                    if (!action.startsWith("refactor:")) throw new IllegalArgumentException("no action " + action);
                    refactor(action.substring("refactor:".length()));
                }
            }
        } catch (RuntimeException failed) {
            say("error: " + failed.getMessage());
            dialogs.tell("Could not " + action, List.of(String.valueOf(failed.getMessage())), editors.caretOnScreen());
        }
    }

    /** Everything bigger or smaller at once: the look's fonts, and the code's. */
    private void zoom(int steps) {
        Zoom.by(steps);
        com.formdev.flatlaf.FlatLaf.updateUI();
        editors.zoomed();
        views.zoomed();
        if (terminal != null) terminal.zoomed();
        console.zoomed();
        say("zoom " + Math.round(Zoom.of(100)) + "%");
    }

    /** Shows the terminal and gives it the keys; it starts the first time it is shown. */
    private void terminal() {
        if (terminal == null) {
            say("no terminal: the IDE was not opened on a folder");
            return;
        }
        views.setSelectedComponent(terminal);
        terminal.requestFocusInWindow();
    }

    /** Where an agent finds this IDE, while it is served. */
    private ide.mcp.Http served;
    private Path servedConfig;
    private Path servedSettings;

    /**
     * The command Claude Code's hook runs after each tool it uses: the event it gets, as it gets it, sent to the IDE's
     * {@code activity} (a step of the task being done), streamed and not as an argument, however large it is.
     */
    static String hook(String url, String token) {
        return "{ printf '%s' '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"activity\",\"arguments\":{\"event\":'; "
                + "cat; printf '%s' '}}}'; } | curl -s -m 3 -o /dev/null -X POST -H 'Content-Type: application/json' "
                + "-H 'Accept: application/json, text/event-stream' -H 'Authorization: Bearer " + token + "' --data-binary @- " + url;
    }

    /**
     * Serves this IDE to agents on this machine over MCP, as it is: what they change, the window shows. Writes where
     * to find it, with its token, into {@code folder}, readable only by the user, and tells the terminal.
     */
    Path serve(Path folder) throws IOException {
        var token = java.util.HexFormat.of().formatHex(random(24));
        served = ide.serve(token, work -> {
            Runnable inTurn = () -> {
                synced(); // what an agent wrote to disk with its own tools, before what it asks of the IDE
                looked(); // where the person is, as the agent may ask
                work.run();
            };
            try {
                if (SwingUtilities.isEventDispatchThread()) inTurn.run();
                else SwingUtilities.invokeAndWait(inTurn);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new IllegalStateException(e.getCause());
            }
        }, tool -> {
            if (!tool.equals("activity")) say("agent: " + tool); // a step shows under its task
            if (!ide.text("diff", Map.of()).isEmpty()) ide.call("save", Map.of()); // what an agent changes reaches disk at once
            refresh();
            pointed();
        });
        java.nio.file.Files.createDirectories(folder);
        servedConfig = folder.resolve("ideseed-" + java.net.URI.create(served.url()).getPort() + ".json");
        var config = Json.write(Map.of("mcpServers", Map.of("ideseed", Map.of("type", "http", "url", served.url(),
                "headers", Map.of("Authorization", "Bearer " + token)))));
        java.nio.file.Files.writeString(servedConfig, config);
        java.nio.file.Files.setPosixFilePermissions(servedConfig, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        servedSettings = folder.resolve("ideseed-" + java.net.URI.create(served.url()).getPort() + "-settings.json");
        java.nio.file.Files.writeString(servedSettings, Json.write(Map.of( // the IDE's own tools need no asking: each is verified and undone
                "permissions", Map.of("allow", List.of("mcp__ideseed")),
                "hooks", Map.of("PostToolUse", List.of(Map.of("matcher", "*",
                        "hooks", List.of(Map.of("type", "command", "command", hook(served.url(), token)))))))));
        java.nio.file.Files.setPosixFilePermissions(servedSettings, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        if (terminal != null) {
            terminal.environment("IDESEED_MCP_CONFIG", servedConfig.toString());
            terminal.environment("IDESEED_SETTINGS", servedSettings.toString());
        }
        say("agents reach this IDE at " + served.url());
        return servedConfig;
    }

    private static byte[] random(int bytes) {
        var seed = new byte[bytes];
        new java.security.SecureRandom().nextBytes(seed);
        return seed;
    }

    /** The last place an agent pointed at that the window showed. */
    private long shownPointed;

    /** Tells the IDE where the person is looking: the current editor, its caret and what is selected. */
    void looked() {
        var path = editors.current();
        ide.call("look", Map.of("path", path.orElse(""), "line", String.valueOf(path.isPresent() ? editors.line() : 0),
                "column", String.valueOf(path.isPresent() ? editors.column() : 0), "selected", editors.selectedLines().orElse("")));
    }

    /** Shows the place an agent pointed at last, if it is new: its lines open and selected, and the note said. */
    void pointed() {
        @SuppressWarnings("unchecked") var pointed = (Map<String, Object>) ide.call("pointed", Map.of());
        var n = ((Number) pointed.get("n")).longValue();
        if (n <= shownPointed) return;
        shownPointed = n;
        var path = String.valueOf(pointed.get("path"));
        var line = ((Number) pointed.get("line")).intValue();
        var end = ((Number) pointed.get("endLine")).intValue();
        show(path, line, end);
        var note = String.valueOf(pointed.get("note"));
        say("look at " + path + ":" + line + (end > line ? "-" + end : "") + (note.isEmpty() ? "" : ": " + note));
    }

    private Watcher watcher;

    /** Follows the project's folder: what another tool or an agent writes there is taken as an edit by hand. */
    void watch() {
        ide.folder().ifPresent(folder -> watcher = new Watcher(folder, this::synced));
    }

    /** Takes what changed on disk; shows the program again if it took anything, and says what it left alone. */
    void synced() {
        if (ide.folder().isEmpty()) return;
        @SuppressWarnings("unchecked") var sync = (Map<String, List<String>>) ide.call("sync", Map.of());
        if (!sync.get("taken").isEmpty()) {
            say("from disk: " + String.join(", ", sync.get("taken")));
            refresh();
        }
        if (!sync.get("conflicts").isEmpty()) {
            say("changed on disk and here, left as here: " + String.join(", ", sync.get("conflicts")) + " (saving writes them)");
        }
        if (!sync.get("refused").isEmpty()) say("the program did not take, from disk: " + String.join(", ", sync.get("refused")));
        if (!sync.get("deleted").isEmpty()) {
            say("deleted on disk, and here: " + String.join(", ", sync.get("deleted")));
            refresh();
        }
    }

    /** Stops serving agents and ends the terminal's program: the window is going away. */
    void closeDown() {
        if (watcher != null) watcher.stop();
        if (ide.folder().isPresent() && terminal != null) { // so that opening the project again goes on with Claude
            ide.call("conversation-open", Map.of("open", String.valueOf(terminal.runs("claude"))));
        }
        if (served != null) {
            served.stop();
            try {
                java.nio.file.Files.deleteIfExists(servedConfig);
                java.nio.file.Files.deleteIfExists(servedSettings);
            } catch (IOException ignored) {
                // a file left behind names a port nobody listens on
            }
            served = null;
        }
        console.stop();
        if (terminal != null) terminal.close();
    }

    /** Where Claude Code keeps what it keeps: its conversations, under {@code projects}, by folder. */
    private Path claudeHome = Path.of(System.getenv().getOrDefault("CLAUDE_CONFIG_DIR",
            Path.of(System.getProperty("user.home"), ".claude").toString()));

    void claudeHome(Path home) {
        this.claudeHome = home;
    }

    /**
     * Where Claude Code keeps the conversation Claude had on this project, if it still keeps it: the one its tools last
     * told the IDE, or else the latest it has of this folder.
     */
    private Optional<Path> conversation() {
        var folder = ide.folder();
        if (folder.isEmpty()) return Optional.empty();
        Path kept;
        try {
            kept = claudeHome.resolve("projects").resolve(folder.get().toRealPath().toString().replaceAll("[^A-Za-z0-9]", "-"));
        } catch (IOException gone) {
            return Optional.empty();
        }
        var told = String.valueOf(ide.record("conversation", Map.of()).get("id"));
        if (!told.isEmpty() && java.nio.file.Files.exists(kept.resolve(told + ".jsonl"))) return Optional.of(kept.resolve(told + ".jsonl"));
        try (var files = java.nio.file.Files.list(kept)) {
            return files.filter(file -> file.toString().endsWith(".jsonl"))
                    .max(java.util.Comparator.comparing(file -> file.toFile().lastModified()));
        } catch (IOException none) {
            return Optional.empty();
        }
    }

    /** Claude Code in the terminal, working on this IDE through MCP: going on with its conversation here, or a new one. */
    private void claude(boolean goOn) {
        if (terminal == null || served == null) {
            say("no Claude Code here: the IDE is not served to agents (it was not opened on a folder)");
            return;
        }
        var kept = goOn ? conversation() : Optional.<Path>empty();
        var resumed = kept.map(file -> file.getFileName().toString().replaceFirst("\\.jsonl$", ""));
        kept.ifPresent(file -> ide.call("conversation-in", Map.of("id", resumed.get(), "transcript", file.toString())));
        try {
            terminal.run("claude" + resumed.map(id -> " --resume " + id).orElse("")
                    + " --mcp-config \"$IDESEED_MCP_CONFIG\" --settings \"$IDESEED_SETTINGS\"");
            resumed.ifPresent(id -> say("going on with Claude's conversation " + id));
        } catch (IOException e) {
            say("the terminal did not take it: " + e.getMessage());
        }
        terminal();
    }

    /** Opened again: if Claude was working on the project when the IDE closed, it goes on. */
    void reopened() {
        if (ide.folder().isPresent() && Boolean.TRUE.equals(ide.record("conversation", Map.of()).get("open"))) claude(true);
    }

    PlansView plans() {
        return plans;
    }

    private java.awt.Point plansOnScreen() {
        try {
            var at = plans.getLocationOnScreen();
            return new java.awt.Point(at.x + 20, at.y + 60);
        } catch (java.awt.IllegalComponentStateException hidden) {
            return new java.awt.Point(0, 0);
        }
    }

    /** Opens lines of a file, selected. */
    void show(String path, int line, int endLine) {
        open(path, line, 1);
        var lines = editors.text(path).split("\n", -1);
        editors.select(line, 1, endLine, Math.max(1, lines[Math.min(endLine, lines.length) - 1].length()));
    }

    private void tell(String message) {
        try {
            terminal.run(message);
            say("told Claude: " + message);
        } catch (IOException e) {
            say("could not tell Claude in the terminal (" + e.getMessage() + "): " + message);
        }
    }

    /** What Claude Code shows while it waits for an answer: what is typed then would answer it. */
    private static final List<String> ASKING = List.of("Enter to confirm", "Do you want to", "Esc to cancel");

    /**
     * What the person says of a plan or a task: the IDE changes it at once; Claude is told in its terminal if it is
     * there, else it is told with the next answer it gets from the IDE.
     */
    void steer(String kind, String plan, String task, String text) {
        var there = terminal != null && terminal.runs("claude");
        var asking = there && ASKING.stream().anyMatch(terminal.screen()::contains);
        var told = there && (!asking || kind.equals("stop")); // Esc answers no to what it asks, as stopping means
        var args = new LinkedHashMap<String, Object>(Map.of("kind", kind, "told", String.valueOf(told)));
        if (!plan.isEmpty()) args.put("plan", plan);
        if (!task.isEmpty()) args.put("task", task);
        if (!text.isEmpty()) args.put("text", text);
        @SuppressWarnings("unchecked") var steer = (Map<String, Object>) ide.call("steer", args);
        var message = String.valueOf(steer.get("message"));
        if (told) {
            try {
                if (kind.equals("stop")) {
                    terminal.type("\u001b"); // Esc: Claude Code stops what it is doing, as when the person presses it
                    var later = new javax.swing.Timer(400, event -> tell(message)); // apart, or Esc and the text are one key
                    later.setRepeats(false);
                    later.start();
                } else {
                    tell(message);
                }
            } catch (IOException e) {
                say("could not tell Claude in the terminal (" + e.getMessage() + "): " + message);
            }
        } else {
            say((asking ? "Claude is asking you something in the terminal; for Claude, at its next step: " : "for Claude, at its next step: ")
                    + message);
        }
        refresh();
    }

    /** The terminal, if there is one. */
    java.util.Optional<Terminal> terminalView() {
        return java.util.Optional.ofNullable(terminal);
    }

    Editors editors() {
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
        arrows(ide.records("mains", Map.of()));
        editors.caret(line, column);
    }

    /** Where programs run in the console below. */
    private final RunConsole console = new RunConsole();

    RunConsole console() {
        return console;
    }

    /** The green arrows of the open files: the class a program runs from, and its main. */
    private void arrows(List<Map<String, Object>> mains) {
        for (var path : editors.open()) {
            var lines = new java.util.ArrayList<Integer>();
            var tip = "";
            for (var main : mains) {
                if (!path.equals(main.get("path"))) continue;
                lines.add(((Number) main.get("line")).intValue());
                lines.add(((Number) main.get("methodLine")).intValue());
                var type = String.valueOf(main.get("type"));
                tip = "Run '" + type.substring(type.lastIndexOf('.') + 1) + ".main()' ("
                        + Keymap.of("run").stroke().toString().replace("pressed ", "") + ")";
            }
            editors.runs(path, lines, tip);
        }
    }

    /** What the editors say and the program does not yet is applied, and the program saved: a run runs it as it is. */
    private void saved() {
        if (!editors.unapplied().isEmpty()) act("apply-edit");
        if (ide.folder().isPresent() && !ide.text("diff", Map.of()).isEmpty()) ide.call("save", Map.of());
    }

    /** Runs the program of a file's class, as IntelliJ runs it: saved first, in the console below. */
    void run(String path) {
        var main = ide.records("mains", Map.of("path", path));
        if (main.isEmpty()) {
            say("nothing to run in " + path + ": no class of it has a main method");
            return;
        }
        start(Map.of("type", String.valueOf(main.getFirst().get("type"))));
    }

    /** Runs a run configuration, saved first, in the console below. */
    void runConfig(String name) {
        start(Map.of("config", name));
    }

    private void start(Map<String, String> what) {
        if (ide.folder().isEmpty()) {
            say("nothing runs here: the IDE was not opened on a folder");
            return;
        }
        saved();
        @SuppressWarnings("unchecked") var run = (Map<String, Object>) ide.call("run-command", what);
        @SuppressWarnings("unchecked") var environment = (Map<String, Object>) run.get("environment");
        var variables = new LinkedHashMap<String, String>();
        environment.forEach((name, value) -> variables.put(name, String.valueOf(value)));
        console.start(String.valueOf(run.get("title")), ((List<?>) run.get("command")).stream().map(String::valueOf).toList(),
                Path.of(String.valueOf(run.get("directory"))), variables);
        views.setSelectedComponent(console);
        configsShown();
    }

    // ---- run configurations, as IntelliJ's toolbar has them

    private static final String EDIT = "Edit Configurations\u2026";
    private final javax.swing.JComboBox<String> configs = new javax.swing.JComboBox<>();
    private boolean listing;

    private JPanel runBar() {
        var bar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 2));
        configs.setPrototypeDisplayValue("A run configuration's name");
        configs.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index, boolean selected,
                                                                   boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setIcon(EDIT.equals(value) || value == null ? null : Icons.of("RUN"));
                if (value == null) setText("Add Configuration\u2026");
                return this;
            }
        });
        configs.addActionListener(event -> {
            if (listing) return;
            if (EDIT.equals(configs.getSelectedItem())) {
                configsShown();
                editConfigs();
            } else if (configs.getSelectedItem() instanceof String chosen) {
                ide.call("run-config-choose", Map.of("name", chosen));
            }
        });
        var run = new javax.swing.JButton(Icons.of("RUN"));
        run.setToolTipText("Run (" + Keymap.of("run-chosen").stroke().toString().replace("pressed ", "") + ")");
        run.addActionListener(event -> act("run-chosen"));
        var stop = new javax.swing.JButton(Icons.of("STOP"));
        stop.setToolTipText("Stop (" + Keymap.of("stop-run").stroke().toString().replace("pressed ", "") + ")");
        stop.addActionListener(event -> act("stop-run"));
        for (var button : List.of(run, stop)) button.putClientProperty("JButton.buttonType", "toolBarButton");
        bar.add(configs);
        bar.add(run);
        bar.add(stop);
        configsShown();
        return bar;
    }

    /** The run configurations in the toolbar's list, the chosen one selected, and editing them last. */
    @SuppressWarnings("unchecked")
    private void configsShown() {
        if (ide.folder().isEmpty()) return;
        var shown = ide.record("run-configs", Map.of());
        listing = true;
        configs.removeAllItems();
        ((List<Map<String, Object>>) shown.get("configs")).forEach(config -> configs.addItem(String.valueOf(config.get("name"))));
        configs.addItem(EDIT);
        var chosen = String.valueOf(shown.get("chosen"));
        configs.setSelectedItem(chosen.isEmpty() ? null : chosen);
        listing = false;
    }

    /** The configurations the toolbar lists, editing them last. */
    List<String> configsListed() {
        var listed = new java.util.ArrayList<String>();
        for (var i = 0; i < configs.getItemCount(); i++) listed.add(configs.getItemAt(i));
        return listed;
    }

    /** IntelliJ's Run/Debug Configurations, a step at a time: which one, then its fields; a new one; removing one. */
    @SuppressWarnings("unchecked")
    private void editConfigs() {
        if (ide.folder().isEmpty()) return;
        var kept = (List<Map<String, Object>>) ide.record("run-configs", Map.of()).get("configs");
        var options = new java.util.ArrayList<Dialogs.Option>();
        kept.forEach(config -> options.add(new Dialogs.Option(String.valueOf(config.get("name")), "RUN", String.valueOf(config.get("type")))));
        options.add(Dialogs.Option.of("+ New configuration", "QUESTION"));
        if (!kept.isEmpty()) options.add(Dialogs.Option.of("\u2212 Remove a configuration", "STOP"));
        var chosen = dialogs.choose("Run Configurations", options, editors.caretOnScreen());
        if (chosen.isEmpty()) return;
        try {
            if (chosen.get().startsWith("\u2212")) {
                dialogs.choose("Remove", kept.stream().map(c -> new Dialogs.Option(String.valueOf(c.get("name")), "STOP", "")).toList(),
                        editors.caretOnScreen()).ifPresent(name -> {
                    ide.call("run-config-remove", Map.of("name", name));
                    say("run configuration removed: " + name);
                });
                configsShown();
                return;
            }
            var was = kept.stream().filter(c -> chosen.get().equals(c.get("name"))).findFirst();
            var start = was.<Map<String, Object>>map(LinkedHashMap::new).orElseGet(() -> {
                var fresh = new LinkedHashMap<String, Object>();
                editors.current().flatMap(path -> ide.records("mains", Map.of("path", path)).stream().findFirst())
                        .ifPresent(main -> fresh.put("type", main.get("type")));
                return fresh;
            });
            var filled = dialogs.fill(was.isPresent() ? "Run configuration " + chosen.get() : "New run configuration",
                    Form.filled("Run configuration", ide.capability("run-config-set"), start), values -> "");
            if (filled.isEmpty()) return;
            ide.call("run-config-set", filled.get());
            var name = String.valueOf(filled.get().get("name")).strip();
            if (was.isPresent() && !name.equals(chosen.get())) ide.call("run-config-remove", Map.of("name", chosen.get())); // renamed
            say("run configuration kept: " + name);
        } catch (RuntimeException refused) {
            dialogs.tell("Could not keep the run configuration", List.of(String.valueOf(refused.getMessage())), editors.caretOnScreen());
        }
        configsShown();
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
                var inside = name + "/";
                var node = new DefaultMutableTreeNode(new Node(name, name.replace('/', '.'), false,
                        worst(problems.stream().filter(p -> String.valueOf(p.get("path")).startsWith(inside)))));
                root.add(node);
                return node;
            });
            var name = path.substring(slash + 1, path.length() - (path.endsWith(".java") ? 5 : 0));
            folder.add(new DefaultMutableTreeNode(new Node(path, name, true, worst(problems.stream().filter(p -> path.equals(p.get("path")))))));
        }
        tree.setRoot(root);
        for (var row = 0; row < project.getRowCount(); row++) project.expandRow(row);
        for (var path : editors.open()) {
            if (files.contains(path)) editors.applied(path, ide.text("show", Map.of("path", path)));
            else editors.close(path); // the program no longer has it: undone, deleted, moved
        }
        editors.problems(problems);
        arrows(ide.records("mains", Map.of()));
        configsShown();
        views.problems(problems);
        plans.refresh();
        views.changes(ide.text("diff", Map.of()));
    }

    /** The problems of what the current editor says and the program does not yet: shown, not applied. */
    void check() {
        var path = editors.current();
        if (path.isEmpty() || !editors.unapplied().containsKey(path.get())) return;
        try {
            problems = ide.records("check", Map.of("path", path.get(), "text", editors.text(path.get())));
        } catch (RuntimeException unchecked) {
            return;
        }
        editors.problems(problems);
        views.problems(problems);
        marks(problems);
        bulb();
    }

    /** The tree's marks again, for these problems. */
    private void marks(List<Map<String, Object>> found) {
        var all = ((DefaultMutableTreeNode) tree.getRoot()).depthFirstEnumeration();
        while (all.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) all.nextElement();
            if (!(node.getUserObject() instanceof Node shown)) continue;
            var severity = worst(found.stream().filter(p -> shown.file() ? shown.path().equals(p.get("path"))
                    : String.valueOf(p.get("path")).startsWith(shown.path() + "/")));
            node.setUserObject(new Node(shown.path(), shown.name(), shown.file(), severity));
            tree.nodeChanged(node);
        }
    }

    /** The menus, laid out as IntelliJ's: each action with its key, each refactoring of the library in its place. */
    JMenuBar menus() {
        var bar = new JMenuBar();
        var file = new JMenu("File");
        var openFolder = new JMenuItem("Open Folder...");
        openFolder.addActionListener(event -> opener.accept(null));
        file.add(openFolder);
        file.addSeparator();
        file.add(item("save"));
        file.add(item("close-editor"));
        bar.add(file);
        menu(bar, "Edit", "undo-change", null, "extend-selection", "shrink-selection", null, "references");
        menu(bar, "View", "terminal", "claude", "claude-new", null, "zoom-in", "zoom-out", "zoom-reset");
        menu(bar, "Navigate", "open-type", "open-declaration", "outline", "go-to-line", null, "hierarchy", "callers", null,
                "next-problem", "previous-problem", null, "back", "forward");
        menu(bar, "Code", "context-actions", "complete", "generate");
        var refactor = new JMenu("Refactor");
        refactor.add(item("refactor"));
        refactor.addSeparator();
        var ids = ide.list("library", Map.of()).stream().map(line -> line.substring(line.indexOf(' ') + 1)).toList();
        var placed = new java.util.HashSet<String>();
        for (var group : List.of(List.of("rename", "change-signature", "move-type"), List.of("extract-method", "introduce-constant", "introduce-field", "extract-superclass", "extract-interface"),
                List.of("inline-method", "collapse-hierarchy"), List.of("pull-up-method", "pull-up-field", "push-down-member", "encapsulate-fields", "introduce-factory"),
                List.of("safe-delete"))) {
            for (var id : group) {
                if (!ids.contains(id)) continue;
                refactor.add(refactoring(id));
                placed.add(id);
            }
            refactor.addSeparator();
        }
        var more = new JMenu("More Refactorings");
        ids.stream().filter(id -> !placed.contains(id) && !ide.list("library", Map.of()).contains("primitive " + id)).forEach(id -> more.add(refactoring(id)));
        refactor.add(more);
        bar.add(refactor);
        menu(bar, "Build", "apply-edit");
        menu(bar, "Run", "run-chosen", "run", "rerun", "stop-run", null, "edit-configs");
        menu(bar, "Help", "find-action", "keys");
        return bar;
    }

    List<String> files() {
        return files;
    }

    /** What the project's tree marks: each file or package with what is wrong in it, by its path. */
    Map<String, String> marks() {
        var marks = new java.util.TreeMap<String, String>();
        var all = ((DefaultMutableTreeNode) tree.getRoot()).depthFirstEnumeration();
        while (all.hasMoreElements()) {
            if (((DefaultMutableTreeNode) all.nextElement()).getUserObject() instanceof Node node && node.severity() != null) {
                marks.put(node.path(), node.severity());
            }
        }
        return marks;
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

    /** Selects a stretch of the current file, as people drag over it: from a line and column to the last character's. */
    void select(int line, int column, int endLine, int endColumn) {
        editors.select(line, column, endLine, endColumn);
    }

    /** What is selected in the current file. */
    String selected() {
        var range = editors.selection();
        return editors.current().map(path -> editors.text(path).substring(range[0], range[1])).orElse("");
    }

    /** Where the caret is: {@code path:line:column}. */
    String where() {
        return editors.current().map(path -> path + ":" + editors.line() + ":" + editors.column()).orElse("");
    }

    /** A menu of actions; {@code null} is a separator. */
    private void menu(JMenuBar bar, String name, String... actions) {
        var menu = new JMenu(name);
        for (var action : actions) {
            if (action == null) menu.addSeparator();
            else menu.add(item(action));
        }
        bar.add(menu);
    }

    private JMenuItem item(String action) {
        var item = new JMenuItem(getActionMap().get(action));
        item.setText(Keymap.of(action).name());
        item.setToolTipText(Keymap.of(action).description());
        item.setAccelerator(Keymap.of(action).stroke());
        return item;
    }

    private java.util.function.Consumer<java.nio.file.Path> opener = folder -> { };

    /** What File, Open Folder does: the window opens another project. */
    void opener(java.util.function.Consumer<java.nio.file.Path> opener) {
        this.opener = opener;
    }

    /**
     * Closes a file's editor, as IDEs do without losing what it says: applied first. What the program does not take yet
     * keeps it open, and the status says why.
     */
    private void close(String path) {
        if (editors.unapplied().containsKey(path) && !applyEdits()) {
            say(path + " stays open: what it says is not applied yet");
            return;
        }
        editors.close(path);
    }

    /**
     * Applies what the editors say and the program does not yet: an edit by hand each, shown as it went. What the
     * program does not take (a text halfway written) stays in its editor as it is, its problems said by the check;
     * false then, and whoever asked does nothing on a program that is not what the editor shows.
     */
    private boolean applyEdits() {
        var unapplied = editors.unapplied();
        if (unapplied.isEmpty()) return true;
        var all = true;
        for (var entry : unapplied.entrySet()) {
            var report = ide.record("edit", Map.of("path", entry.getKey(), "text", entry.getValue()));
            views.steps(strings(report.get("steps")));
            if (Boolean.TRUE.equals(report.get("applied"))) {
                editors.applied(entry.getKey(), entry.getValue());
            } else {
                all = false;
                say(entry.getKey() + " is not applied yet: " + String.join("; ", strings(report.get("violations"))));
            }
        }
        refresh();
        if (!all) check();
        return all;
    }

    /**
     * A refactoring's form, preloaded with the element under the caret or the one it is inside, the lines selected and
     * what the refactoring suggests; and the refactoring with what it is given.
     */
    private void refactor(String capability) {
        if (!applyEdits()) return;
        var form = Form.of(ide.capability(capability), ide, candidates(), editors.selectedLines());
        dialogs.fill(form.title(), form, values -> preview(capability, form.args(values)))
                .ifPresent(values -> change(capability, form.args(values)));
    }

    /** What a refactoring would change: its diff, or why it would not apply. */
    private String preview(String capability, Map<String, Object> args) {
        try {
            var report = ide.record("preview", Map.of("refactoring", capability, "args", args));
            return Boolean.TRUE.equals(report.get("applied")) ? String.valueOf(report.get("diff"))
                    : "not applied:\n" + String.join("\n", strings(report.get("violations")));
        } catch (RuntimeException failed) {
            return "error: " + failed.getMessage();
        }
    }

    /**
     * What can be done where the caret is, as IntelliJ's Alt+Enter offers it: the quick fixes of the line's problems
     * first, then the refactorings that take the element under the caret or the one it is inside.
     */
    private void contextActions() {
        var path = editors.current().orElseThrow(() -> new IllegalStateException("no file is open"));
        var line = String.valueOf(editors.line());
        if (!applyEdits()) return;
        var offered = new java.util.ArrayList<Dialogs.Option>();
        ide.list("fixes", Map.of("path", path, "line", line)).forEach(title -> offered.add(Dialogs.Option.of(title, "FIX")));
        var fixes = offered.size();
        refactorings(candidates()).forEach(id -> offered.add(new Dialogs.Option(label(id), "REFACTORING", ide.capability(id).description())));
        if (offered.isEmpty()) {
            say("nothing to do at " + path + ":" + line);
            return;
        }
        var at = editors.caretOnScreen();
        dialogs.choose("Context Actions", offered, at).ifPresent(chosen -> {
            if (offered.subList(0, fixes).stream().noneMatch(option -> option.text().equals(chosen))) {
                refactorLabelled(chosen);
                return;
            }
            var answers = new java.util.LinkedHashMap<String, Object>();
            for (var ask : ide.records("asks", Map.of("path", path, "line", line, "title", chosen))) {
                var answer = dialogs.line(String.valueOf(ask.get("question")), String.valueOf(ask.get("default")), at);
                if (answer.isEmpty()) return;
                answers.put(String.valueOf(ask.get("name")), answer.get());
            }
            change("fix", Map.of("path", path, "line", line, "title", chosen, "answers", answers));
        });
    }

    /**
     * The bulb at the caret's line: red when the line has an error, yellow when it has a warning or the caret is on or
     * inside something a refactoring takes; none while the editor says something not applied yet, which the program does not know.
     */
    private void bulb() {
        var path = editors.current();
        if (path.isEmpty()) return;
        var line = editors.line();
        var here = problems.stream().filter(p -> path.get().equals(p.get("path")) && ((Number) p.get("line")).intValue() == line).toList();
        if (!here.isEmpty()) { // red for an error, yellow for a warning, as IntelliJ's
            editors.bulb(here.stream().anyMatch(p -> !"WARNING".equals(p.get("severity"))) ? "FIX" : "INTENTION");
            return;
        }
        if (!editors.unapplied().isEmpty()) {
            editors.bulb(null);
            return;
        }
        try {
            editors.bulb(refactorings(candidates()).isEmpty() ? null : "INTENTION");
        } catch (RuntimeException unknown) {
            editors.bulb(null);
        }
    }

    /** Which bulb the caret's line shows now, asked at once: FIX, INTENTION, or nothing. */
    Optional<String> bulbShown() {
        bulb();
        return editors.bulb();
    }

    /** The editor's right-click menu, as IntelliJ's: context actions, going places, and the refactorings of the caret. */
    private javax.swing.JPopupMenu contextMenu() {
        var menu = new javax.swing.JPopupMenu();
        menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
                menu.removeAll();
                menu.add(item("context-actions"));
                menu.addSeparator();
                for (var action : List.of("open-declaration", "references", "hierarchy", "callers")) menu.add(item(action));
                menu.addSeparator();
                var refactor = new JMenu("Refactor");
                try {
                    for (var id : refactorings(candidates())) refactor.add(refactoring(id));
                } catch (RuntimeException unknown) {
                    // nothing to refactor where the program does not know the text yet
                }
                refactor.setEnabled(refactor.getItemCount() > 0);
                menu.add(refactor);
                menu.add(item("generate"));
                menu.addSeparator();
                for (var action : List.of("extend-selection", "complete", "apply-edit")) menu.add(item(action));
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
            }
        });
        return menu;
    }

    /** Selects what the selection is inside, a step wider each time, as IntelliJ's Ctrl+W does. */
    private void extendSelection() {
        var path = editors.current().orElseThrow(() -> new IllegalStateException("no file is open"));
        if (!applyEdits()) return;
        var now = editors.selection();
        var spans = ide.records("around", Map.of("path", path, "line", String.valueOf(editors.line()), "column", String.valueOf(editors.column())));
        for (var span : spans) {
            var start = editors.offset(number(span.get("line")), number(span.get("column")));
            var end = editors.offset(number(span.get("endLine")), number(span.get("endColumn"))) + 1;
            if (start <= now[0] && end >= now[1] && (start < now[0] || end > now[1])) {
                widened.push(now);
                editors.select(new int[] {start, end});
                return;
            }
        }
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    /** What the caret is on, then what it is inside: the candidates a refactoring's element fields take, in order. */
    private List<Form.Selection> candidates() {
        var candidates = new java.util.ArrayList<Form.Selection>();
        selection().ifPresent(candidates::add);
        var path = editors.current();
        if (path.isPresent()) {
            var inside = ide.record("inside", Map.of("path", path.get(), "line", String.valueOf(editors.line()), "column", String.valueOf(editors.column())));
            if (inside != null) {
                var element = new Form.Selection(String.valueOf(inside.get("element")), String.valueOf(inside.get("kind")));
                if (!candidates.contains(element)) candidates.add(element);
            }
        }
        return candidates;
    }

    /** The refactorings whose first element parameter takes one of the candidates, the primitives last. */
    private List<String> refactorings(List<Form.Selection> candidates) {
        if (candidates.isEmpty()) return List.of();
        var listing = ide.list("library", Map.of());
        return java.util.stream.Stream.concat(listing.stream().filter(line -> !line.startsWith("primitive ")),
                        listing.stream().filter(line -> line.equals("primitive rename")))
                .map(line -> line.substring(line.indexOf(' ') + 1))
                .filter(id -> candidates.stream().anyMatch(candidate -> takes(ide.capability(id), candidate))).toList();
    }

    /** A refactoring as a menu names it: {@code pull-up-method} is "Pull Up Method...". */
    private static String label(String id) {
        var words = java.util.Arrays.stream(id.split("-")).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).toList();
        return String.join(" ", words) + "...";
    }

    private void refactorLabelled(String label) {
        var id = ide.list("library", Map.of()).stream().map(line -> line.substring(line.indexOf(' ') + 1))
                .filter(candidate -> label(candidate).equals(label) || candidate.equals(label)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no refactoring " + label));
        if (id.equals("rename")) rename();
        else refactor(id);
    }

    private JMenuItem refactoring(String id) {
        var item = new JMenuItem(label(id), Icons.of("REFACTORING"));
        item.setToolTipText(ide.capability(id).description());
        Keymap.KEYS.stream().filter(key -> key.action().equals(id) || id.equals("inline-method") && key.action().equals("inline"))
                .findFirst().ifPresent(key -> item.setAccelerator(key.stroke()));
        item.addActionListener(event -> act(Keymap.KEYS.stream().anyMatch(key -> key.action().equals(id)) ? id
                : id.equals("inline-method") ? "inline" : "refactor:" + id));
        return item;
    }

    /** A new name for the element under the caret, written where it is, as IDEs rename in place. */
    private void rename() {
        if (!applyEdits()) return;
        var selection = selection();
        if (selection.isEmpty()) {
            say("rename: the caret is on no element");
            return;
        }
        editors.markAll(editors.name()); // what the rename changes in this file, marked while the new name is asked
        renaming = editors.marked();
        try {
            dialogs.line("Rename " + selection.get().element(), editors.name(), editors.caretOnScreen())
                    .ifPresent(name -> change("rename", Map.of("element", selection.get().element(), "name", name)));
        } finally {
            editors.markAll(null);
        }
    }

    /** Runs a change after applying what the editors say, and shows what it did. */
    private void change(String capability, Map<String, ?> args) {
        if (!applyEdits()) return;
        shown(capability, ide.record(capability, args));
        refresh();
    }

    private void shown(String what, Map<String, Object> report) {
        views.steps(strings(report.get("steps")));
        var applied = Boolean.TRUE.equals(report.get("applied"));
        say(what + (applied ? ": applied, " + report.get("guarantee") : ": not applied: " + String.join("; ", strings(report.get("violations")))));
        if (!applied) dialogs.tell(what + " was not applied", strings(report.get("violations")), editors.caretOnScreen());
    }

    /** The element under the caret, with its kind; what the editors say is applied first, so that it is the program's. */
    private Optional<Form.Selection> selection() {
        if (!applyEdits()) return Optional.empty();
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

    private void openProblem(Views.Problem problem) {
        remember();
        open(problem.path(), problem.line(), problem.column());
    }

    /** A result names an element, maybe indented or marked as the focus ({@code > }). */
    private void openResult(String result) {
        go(result.strip().replaceFirst("^> ", ""));
    }

    /** Goes to the next problem of the file (or the previous one), where it is on its line, errors and warnings alike. */
    private void problem(int direction) {
        var path = editors.current().orElseThrow(() -> new IllegalStateException("no file is open"));
        var places = problems.stream().filter(p -> path.equals(p.get("path")))
                .map(p -> new int[] {((Number) p.get("line")).intValue(), Math.max(1, p.get("column") instanceof Number n ? n.intValue() : 1)})
                .sorted(java.util.Comparator.<int[]>comparingInt(at -> at[0]).thenComparingInt(at -> at[1])).toList();
        if (places.isEmpty()) {
            say("no problems in " + path);
            return;
        }
        var here = new int[] {editors.line(), editors.column()};
        java.util.function.Predicate<int[]> after = at -> at[0] > here[0] || at[0] == here[0] && at[1] > here[1];
        java.util.function.Predicate<int[]> before = at -> at[0] < here[0] || at[0] == here[0] && at[1] < here[1];
        var next = direction > 0 ? places.stream().filter(after).findFirst().orElse(places.getFirst())
                : places.reversed().stream().filter(before).findFirst().orElse(places.getLast());
        editors.caret(next[0], next[1]);
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
