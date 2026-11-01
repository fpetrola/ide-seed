package ide.ui;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import java.awt.Color;
import java.awt.Font;

/** A diff as IDEs show one: added lines green, removed ones red, each file and each hunk marked. */
final class DiffPane extends JTextPane {

    DiffPane() {
        setEditable(false);
        setFont(Zoom.code(13));
        setBackground(new Color(0x2B2B2B));
    }

    void show(String diff) {
        setText("");
        var document = getStyledDocument();
        for (var line : diff.lines().toList()) {
            var style = new SimpleAttributeSet();
            StyleConstants.setForeground(style, new Color(0xA9B7C6));
            if (line.startsWith("+++") || line.startsWith("---")) {
                StyleConstants.setBold(style, true);
                StyleConstants.setForeground(style, new Color(0xCCCCCC));
            } else if (line.startsWith("@@")) {
                StyleConstants.setForeground(style, new Color(0x9876AA));
            } else if (line.startsWith("+")) {
                StyleConstants.setForeground(style, new Color(0x9BCB8A));
                StyleConstants.setBackground(style, new Color(0x294436));
            } else if (line.startsWith("-")) {
                StyleConstants.setForeground(style, new Color(0xE5A3A3));
                StyleConstants.setBackground(style, new Color(0x4B2A2A));
            }
            try {
                document.insertString(document.getLength(), line + "\n", style);
            } catch (BadLocationException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        setCaretPosition(0);
    }
}
