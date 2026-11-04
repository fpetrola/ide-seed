package ide.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;

import static org.assertj.core.api.Assertions.assertThat;

/** The project's files on the left, as IntelliJ shows them: a file with errors, and each folder it is in, underlined red. */
class FileTreeTest {

    private final Workbench.FileRenderer renderer = new Workbench.FileRenderer();
    private final JTree tree = new JTree();

    private Workbench.FileRenderer shown(String severity) {
        var node = new DefaultMutableTreeNode(new Workbench.Node("a/A.java", "A", true, severity));
        return (Workbench.FileRenderer) renderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false);
    }

    @Test
    void aFileWithErrorsIsUnderlinedRedAndOneWithWarningsOrNothingIsNot() {
        assertThat(shown("ERROR").underlined()).isTrue();
        assertThat(shown("WARNING").underlined()).isFalse();
        assertThat(shown(null).underlined()).isFalse();
    }
}
