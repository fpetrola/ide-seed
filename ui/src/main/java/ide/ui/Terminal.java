package ide.ui;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A terminal as IntelliJ's, in the project's folder: JediTerm draws it, pty4j gives the program a real terminal (so
 * that one that asks, as Claude Code does, finds it). The user's shell starts the first time it is shown; it keeps
 * its keys, so the window's do nothing while it has the focus.
 */
final class Terminal extends JPanel {

    private final Path folder;
    private final JediTermWidget widget;
    private final java.util.Map<String, String> environment = new HashMap<>();
    /** What it runs when it is first shown: the user's shell, or a line run in it first. */
    private List<String> first = shell();

    Terminal(Path folder) {
        super(new BorderLayout());
        this.folder = folder;
        this.widget = new JediTermWidget(80, 24, new Look());
        add(widget, BorderLayout.CENTER);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && !running()) {
                start(first);
            }
        });
    }

    /** The user's shell, as a login shell: the same programs and paths as in a terminal of their own. */
    static List<String> shell() {
        var shell = System.getenv().getOrDefault("SHELL", "/bin/bash");
        return List.of(shell, "-l");
    }

    /** A variable the programs it starts will have. */
    void environment(String name, String value) {
        environment.put(name, value);
    }

    /**
     * Runs a line in the shell: typed into it if it is running, Enter a moment after (as a person types it), or, if it
     * has not started, run first by an interactive shell that then stays.
     */
    void run(String line) throws IOException {
        if (running()) {
            type(line);
            var enter = new javax.swing.Timer(150, event -> { // apart: a line and its Enter at once are a paste, not sent
                try {
                    type("\r");
                } catch (IOException gone) {
                    // the program ended meanwhile
                }
            });
            enter.setRepeats(false);
            enter.start();
            return;
        }
        var shell = shell().getFirst();
        first = List.of(shell, "-l", "-i", "-c", line + "; exec " + shell + " -l");
        if (isShowing()) start(first);
    }

    /** What it will run when it is first shown. */
    List<String> first() {
        return first;
    }

    void start(List<String> command) {
        var environment = new HashMap<>(System.getenv());
        // started from inside Claude Code, the IDE would hand down its marks: a Claude here would think it is a part of that one
        environment.keySet().removeIf(name -> name.startsWith("CLAUDECODE") || name.startsWith("CLAUDE_CODE_"));
        environment.putAll(this.environment);
        environment.put("TERM", "xterm-256color");
        environment.put("TERMINAL_EMULATOR", "IdeSeed");
        try {
            var process = new PtyProcessBuilder(command.toArray(String[]::new))
                    .setDirectory(folder.toString()).setEnvironment(environment)
                    .setInitialColumns(Math.max(80, widget.getTerminalPanel().getWidth() / 8)).setInitialRows(24)
                    .start();
            widget.setTtyConnector(new Connector(process, command));
            widget.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + command, e);
        }
    }

    boolean running() {
        return widget.getTtyConnector() != null && widget.getTtyConnector().isConnected();
    }

    /**
     * Whether a program of that name runs in the terminal now: the one it started or one started from it (a command
     * the shell runs, or a script an interpreter runs), by the name of its file.
     */
    boolean runs(String program) {
        if (!(widget.getTtyConnector() instanceof Connector connector) || !connector.process.isAlive()) return false;
        return ProcessHandle.of(connector.process.pid()) // pty4j's processes give no handle of their own
                .map(started -> java.util.stream.Stream.concat(java.util.stream.Stream.of(started), started.descendants())
                        .anyMatch(process -> named(process, program)))
                .orElse(false);
    }

    /**
     * Whether a process is that program: its executable is in a folder or a file of that name (an installed program is
     * often a link to {@code .../claude/versions/2.1.3}), it was called by that name, or it runs a script of that name.
     */
    private static boolean named(ProcessHandle process, String program) {
        var info = process.info();
        java.util.function.Predicate<String> file = path -> name(path).equals(program);
        java.util.function.Predicate<String> along = path -> {
            try {
                for (var part : java.nio.file.Path.of(path)) if (part.toString().equals(program)) return true;
                return false;
            } catch (java.nio.file.InvalidPathException notAPath) {
                return false;
            }
        };
        return info.command().filter(along).isPresent() || info.arguments().stream().flatMap(java.util.Arrays::stream).anyMatch(file)
                || calledAs(process).filter(file).isPresent();
    }

    private static String name(String path) {
        try {
            var name = java.nio.file.Path.of(path).getFileName();
            return name == null ? "" : name.toString();
        } catch (java.nio.file.InvalidPathException notAPath) {
            return "";
        }
    }

    /** The name a process was called by, where the system says it (Linux). */
    private static Optional<String> calledAs(ProcessHandle process) {
        try {
            var line = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc", String.valueOf(process.pid()), "cmdline"));
            var end = 0;
            while (end < line.length && line[end] != 0) end++;
            return end == 0 ? Optional.empty() : Optional.of(new String(line, 0, end, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException elsewhere) {
            return Optional.empty();
        }
    }

    /** Sends text as if it were typed. */
    void type(String text) throws IOException {
        widget.getTtyConnector().write(text);
    }

    /** What the screen shows, once it satisfies {@code ready} or when {@code patience} runs out. */
    String await(Predicate<String> ready, Duration patience) throws InterruptedException {
        var until = System.nanoTime() + patience.toNanos();
        var screen = screen();
        while (!ready.test(screen) && System.nanoTime() < until) {
            Thread.sleep(20);
            screen = screen();
        }
        return screen;
    }

    String screen() {
        var buffer = widget.getTerminalTextBuffer();
        buffer.lock();
        try {
            return buffer.getScreenLines();
        } finally {
            buffer.unlock();
        }
    }

    /** Ends the program the terminal runs. */
    void close() {
        widget.close();
    }

    /** The code's font at the current zoom. */
    void zoomed() {
        widget.getTerminalPanel().setFont(Zoom.code(13));
        widget.getTerminalPanel().revalidate();
    }

    @Override
    public boolean requestFocusInWindow() {
        return widget.getTerminalPanel().requestFocusInWindow();
    }

    /** The window's look: the code's font and the editor's colors. */
    private static final class Look extends DefaultSettingsProvider {

        @Override
        public Font getTerminalFont() {
            return Zoom.code(13);
        }

        @Override
        public float getTerminalFontSize() {
            return Zoom.code(13).getSize2D();
        }

        @Override
        public TerminalColor getDefaultForeground() {
            return of(UIManager.getColor("TextArea.foreground"), new Color(0xA9B7C6));
        }

        @Override
        public TerminalColor getDefaultBackground() {
            return of(UIManager.getColor("TextArea.background"), new Color(0x2B2B2B));
        }

        @Override
        public TextStyle getDefaultStyle() {
            return new TextStyle(getDefaultForeground(), getDefaultBackground());
        }

        @Override
        public TextStyle getSelectionColor() {
            var selection = UIManager.getColor("TextArea.selectionBackground");
            return new TextStyle(getDefaultForeground(), of(selection, new Color(0x214283)));
        }

        @Override
        public boolean useInverseSelectionColor() {
            return false;
        }

        private static TerminalColor of(Color color, Color otherwise) {
            var c = color == null ? otherwise : color;
            return TerminalColor.rgb(c.getRed(), c.getGreen(), c.getBlue());
        }
    }

    /** A program on a pty: what it writes is read as UTF-8, and it learns the terminal's size when it changes. */
    private static final class Connector extends ProcessTtyConnector {

        private final PtyProcess process;

        Connector(PtyProcess process, List<String> command) {
            super(process, StandardCharsets.UTF_8, command);
            this.process = process;
        }

        @Override
        public String getName() {
            return "Local";
        }

        @Override
        public boolean isConnected() {
            return process.isAlive();
        }

        @Override
        public void resize(TermSize size) {
            if (process.isAlive()) process.setWinSize(new WinSize(size.getColumns(), size.getRows()));
        }
    }
}
