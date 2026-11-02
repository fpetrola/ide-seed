package ide.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Where a program runs, as IntelliJ's Run window: what it writes to its standard output, and to its standard error in
 * red, as it writes it; what is typed below goes to its standard input; and how it finished. It can be stopped, and
 * run again as it was. It runs a command it is given: what to run, the IDE says.
 */
final class RunConsole extends JPanel {

    private final JTextPane text = new JTextPane();
    private final JTextField input = new JTextField();
    private final JButton rerun = new JButton(Icons.of("RUN"));
    private final JButton stop = new JButton(Icons.of("STOP"));
    private final SimpleAttributeSet out = new SimpleAttributeSet();
    private final SimpleAttributeSet err = new SimpleAttributeSet();
    private final SimpleAttributeSet said = new SimpleAttributeSet();
    private final SimpleAttributeSet typed = new SimpleAttributeSet();
    private final StringBuilder errors = new StringBuilder();
    private Process process;
    private List<String> command = List.of();
    private Path directory;
    private String title = "";
    private Integer exit;

    RunConsole() {
        super(new BorderLayout());
        text.setEditable(false);
        text.setFont(Zoom.code(13));
        StyleConstants.setForeground(err, new Color(0xFF6B68));
        StyleConstants.setForeground(said, new Color(0x808080));
        StyleConstants.setForeground(typed, new Color(0x6A8759));
        var bar = new JToolBar(JToolBar.VERTICAL);
        bar.setFloatable(false);
        rerun.setToolTipText("Rerun (" + Keymap.of("rerun").stroke().toString().replace("pressed ", "") + ")");
        stop.setToolTipText("Stop (" + Keymap.of("stop-run").stroke().toString().replace("pressed ", "") + ")");
        rerun.addActionListener(event -> rerun());
        stop.addActionListener(event -> stop());
        bar.add(rerun);
        bar.add(stop);
        add(bar, BorderLayout.LINE_START);
        var scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        input.putClientProperty("JTextField.placeholderText", "Input for the program, Enter sends it");
        input.addActionListener(event -> send(input.getText()));
        add(input, BorderLayout.PAGE_END);
        setMinimumSize(new java.awt.Dimension(0, 0)); // the views below take the height they are given, not more
        running(false);
    }

    /** Runs a command in a folder, stopping what ran before; what it writes is shown as it writes it. */
    void start(String title, List<String> command, Path directory) {
        stop();
        this.title = title;
        this.command = List.copyOf(command);
        this.directory = directory;
        text.setText("");
        errors.setLength(0);
        exit = null;
        append(String.join(" ", command) + "\n", said);
        try {
            process = new ProcessBuilder(command).directory(directory.toFile()).start();
        } catch (IOException e) {
            append("Cannot run: " + e.getMessage() + "\n", err);
            return;
        }
        var running = process;
        running(true);
        var readers = List.of(read(running.getInputStream(), out), read(running.getErrorStream(), err));
        running.onExit().thenAccept(ended -> {
            for (var reader : readers) { // what it wrote before it ended is shown before how it ended
                try {
                    reader.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            SwingUtilities.invokeLater(() -> finished(ended));
        });
    }

    private void finished(Process ended) {
        {
            if (ended != process) return;
            exit = ended.exitValue();
            append("\nProcess finished with exit code " + exit + "\n", said);
            running(false);
        }
    }

    /** Runs again what ran last. */
    void rerun() {
        if (!command.isEmpty()) start(title, command, directory);
    }

    /** Ends the program, and what it started, at once. */
    void stop() {
        var running = process;
        if (running == null || !running.isAlive()) return;
        running.descendants().forEach(ProcessHandle::destroy);
        running.destroy();
        try {
            if (!running.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) running.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A line for the program's standard input. */
    void send(String line) {
        if (process == null || !process.isAlive()) return;
        try {
            OutputStream in = process.getOutputStream();
            in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
            append(line + "\n", typed);
            input.setText("");
        } catch (IOException e) {
            append("The program takes no more input: " + e.getMessage() + "\n", err);
        }
    }

    private Thread read(InputStream stream, SimpleAttributeSet style) {
        var reader = new Thread(() -> {
            try (var in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var buffer = new char[4096];
                for (int n; (n = in.read(buffer)) >= 0; ) {
                    var chunk = new String(buffer, 0, n);
                    if (style == err) synchronized (errors) {
                        errors.append(chunk);
                    }
                    SwingUtilities.invokeLater(() -> append(chunk, style));
                }
            } catch (IOException closed) {
                // the program ended
            }
        }, "run " + title);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    private void append(String chunk, SimpleAttributeSet style) {
        var document = text.getStyledDocument();
        try {
            if (style == out) StyleConstants.setForeground(out, UIManager.getColor("TextPane.foreground"));
            document.insertString(document.getLength(), chunk, style);
            text.setCaretPosition(document.getLength());
        } catch (BadLocationException never) {
            // always at the end
        }
    }

    private void running(boolean running) {
        stop.setEnabled(running);
        input.setEnabled(running);
        rerun.setEnabled(!command.isEmpty());
    }

    void zoomed() {
        text.setFont(Zoom.code(13));
    }

    // ---- as the window and its tests read it

    String title() {
        return title;
    }

    boolean running() {
        return process != null && process.isAlive();
    }

    /** Everything shown: the command, what the program wrote, and how it finished. */
    String text() {
        return text.getText();
    }

    /** What the program wrote to its standard error. */
    String errors() {
        synchronized (errors) {
            return errors.toString();
        }
    }

    /** How it finished, once it did and that was shown. */
    java.util.Optional<Integer> exit() {
        return java.util.Optional.ofNullable(exit);
    }

    /** Waits, letting Swing's thread show what comes, until the program finished and that was shown. */
    void await(Duration patience) throws Exception {
        var until = System.nanoTime() + patience.toNanos();
        while (System.nanoTime() < until) {
            var done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = exit != null);
            if (done[0]) {
                SwingUtilities.invokeAndWait(() -> { }); // what was read before the end is shown
                return;
            }
            Thread.sleep(20);
        }
    }
}
