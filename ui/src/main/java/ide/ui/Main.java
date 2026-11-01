package ide.ui;

import ide.session.Workspace;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.nio.file.Path;

/** {@code java -jar ide-ui.jar [folder]}: the project in the folder (or one to choose) in a window. */
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
            var workbench = new Workbench(new Ide(Workspace.open(root)), new SwingDialogs(frame));
            frame.setJMenuBar(workbench.menus());
            frame.setContentPane(workbench);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1400, 900);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static Path folder() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("A folder of Java sources");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        return chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
    }
}
