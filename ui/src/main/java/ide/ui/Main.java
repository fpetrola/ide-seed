package ide.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import ide.session.Workspace;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * {@code java -jar ide-ui.jar [folder]}: the project in the folder in a window that looks like IntelliJ's (Darcula,
 * Inter, JetBrains Mono); with no folder, the example project, copied to {@code ~/.ideseed/example} the first time.
 * The window shows at once, while the sources are read and compiled out of its way; if they cannot be, it says why.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FlatInterFont.install();
            FlatJetBrainsMonoFont.install();
            FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
            FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);
            UIManager.put("ScrollBar.width", 10);
            UIManager.put("TabbedPane.showTabSeparators", true);
            FlatDarculaLaf.setup();
            Zoom.start();
            FlatLaf.updateUI();
            var frame = new JFrame();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1400, 900);
            frame.setLocationRelativeTo(null);
            open(frame, args.length > 0 ? Path.of(args[0]) : example());
        });
    }

    /** Shows a project in the window: the window says what it reads, then shows the project, or why it cannot. */
    static void open(JFrame frame, Path root) {
        frame.setTitle("IdeSeed - " + root.toAbsolutePath().normalize());
        frame.setJMenuBar(null);
        frame.setContentPane(new JLabel("Reading the Java sources under " + root + ", and asking Maven for their libraries ...",
                SwingConstants.CENTER));
        frame.revalidate();
        frame.setVisible(true);
        new SwingWorker<Ide, Void>() {
            @Override
            protected Ide doInBackground() {
                var ide = new Ide(Workspace.open(root));
                ide.call("elements", Map.of()); // the program read, and
                ide.call("diagnostics", Map.of()); // compiled, before the window asks
                return ide;
            }

            @Override
            protected void done() {
                try {
                    var workbench = new Workbench(get(), new SwingDialogs(frame));
                    workbench.opener(folder -> {
                        var chosen = folder == null ? folder(frame) : folder;
                        if (chosen != null) open(frame, chosen);
                    });
                    frame.setJMenuBar(workbench.menus());
                    frame.setContentPane(workbench);
                    frame.revalidate();
                } catch (InterruptedException | ExecutionException | RuntimeException failed) {
                    JOptionPane.showMessageDialog(frame, failure(failed, root), "IdeSeed could not open it", JOptionPane.ERROR_MESSAGE);
                    var chosen = folder(frame);
                    if (chosen == null) System.exit(1);
                    open(frame, chosen);
                }
            }
        }.execute();
    }

    /** What went wrong opening a folder, and, when the sources are too many, what to open instead. */
    static String failure(Throwable failed, Path root) {
        var cause = failed instanceof ExecutionException wrapped && wrapped.getCause() != null ? wrapped.getCause() : failed;
        if (cause instanceof OutOfMemoryError) {
            return "The Java sources under " + root + " do not fit in memory.\n"
                    + "Open a module's source root, such as <module>/src/main/java, or give Java more memory (-Xmx).";
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    /** The example project, copied from the IDE to {@code ~/.ideseed/example} unless it is there already. */
    static Path example() {
        var root = Path.of(System.getProperty("user.home"), ".ideseed", "example");
        return Files.isDirectory(root) ? root : example(root);
    }

    /** The example project, copied to {@code root}. */
    static Path example(Path root) {
        try (var index = Main.class.getResourceAsStream("/ide/ui/example/files.txt")) {
            for (var file : new String(index.readAllBytes(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList()) {
                try (var in = Main.class.getResourceAsStream("/ide/ui/example/" + file)) {
                    var target = root.resolve(file);
                    Files.createDirectories(target.getParent());
                    Files.write(target, in.readAllBytes());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return root;
    }

    private static Path folder(JFrame frame) {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("A folder of Java sources, such as a project or a module's src/main/java");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }
}
