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
import java.util.function.Predicate;

/**
 * A terminal as IntelliJ's, in the project's folder: JediTerm draws it, pty4j gives the program a real terminal (so
 * that one that asks, as Claude Code does, finds it). The user's shell starts the first time it is shown; it keeps
 * its keys, so the window's do nothing while it has the focus.
 */
final class Terminal extends JPanel {

    private final Path folder;
    private final JediTermWidget widget;

    Terminal(Path folder) {
        super(new BorderLayout());
        this.folder = folder;
        this.widget = new JediTermWidget(80, 24, new Look());
        add(widget, BorderLayout.CENTER);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && !running()) {
                start(shell());
            }
        });
    }

    /** The user's shell, as a login shell: the same programs and paths as in a terminal of their own. */
    static List<String> shell() {
        var shell = System.getenv().getOrDefault("SHELL", "/bin/bash");
        return List.of(shell, "-l");
    }

    void start(List<String> command) {
        var environment = new HashMap<>(System.getenv());
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
