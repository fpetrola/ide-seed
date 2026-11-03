package ide.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;

/**
 * A diff as IDEs show one: the code highlighted as Java, added lines on green, removed ones on red, each file's header
 * and each hunk marked, the marks under the highlighting.
 */
final class DiffPane extends RSyntaxTextArea {

    DiffPane() {
        setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        Code.theme(this);
        setEditable(false);
        setLineWrap(false);
        setHighlightCurrentLine(false);
        setCodeFoldingEnabled(false);
        Code.sideways(this);
    }

    void show(String diff) {
        removeAllLineHighlights();
        setText(diff);
        var lines = diff.lines().toList();
        for (var i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) Code.mark(this, i, Code.HEADER);
            else if (line.startsWith("+")) Code.mark(this, i, Code.ADDED);
            else if (line.startsWith("-")) Code.mark(this, i, Code.REMOVED);
        }
        setCaretPosition(0);
    }
}
