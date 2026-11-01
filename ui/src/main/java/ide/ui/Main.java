package ide.ui;

import ide.session.Workspace;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * {@code java -jar ide-ui.jar [folder]}: the project in the folder (or one to choose) in a window. The window shows at
 * once, while the sources are read and compiled out of its way; if they cannot be, it says why.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
                // the default look is fine too
            }
            var root = args.length > 0 ? Path.of(args[0]) : folder();
            if (root == null) System.exit(0);
            var frame = new JFrame("IdeSeed: " + root.toAbsolutePath().normalize());
            frame.setContentPane(new JLabel("Reading the Java sources under " + root + " ...", SwingConstants.CENTER));
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1400, 900);
            frame.setLocationRelativeTo(null);
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
                        frame.setJMenuBar(workbench.menus());
                        frame.setContentPane(workbench);
                        frame.revalidate();
                    } catch (InterruptedException | ExecutionException | RuntimeException failed) {
                        JOptionPane.showMessageDialog(frame, failure(failed, root), "IdeSeed could not open it", JOptionPane.ERROR_MESSAGE);
                        System.exit(1);
                    }
                }
            }.execute();
        });
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

    private static Path folder() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("A folder of Java sources, such as a module's src/main/java");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }
}
