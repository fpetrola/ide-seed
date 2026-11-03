package ide.ui;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;

import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Code shown to be read, wherever it is shown: Java as the editors color it (Darcula), not editable, not wrapped, and
 * lines marked by a color under the text, so that what a diff or a replay marks goes on top of the highlighting.
 */
final class Code {

    static final Color REMOVED = new Color(0x4B2A2A);
    static final Color ADDED = new Color(0x294436);
    static final Color CHANGED = new Color(0x45402A);
    static final Color MOVED = new Color(0x2F3B5C);
    static final Color ZONE = new Color(0x52503A);
    static final Color NONE = new Color(0x313335);
    static final Color HEADER = new Color(0x3C3F41);

    private Code() {
    }

    /** An area of Java to read. */
    static RSyntaxTextArea area() {
        var area = new RSyntaxTextArea();
        area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
        theme(area);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setHighlightCurrentLine(false);
        area.setCodeFoldingEnabled(false);
        area.setAntiAliasingEnabled(true);
        return sideways(area);
    }

    static void theme(RSyntaxTextArea area) {
        try (var in = Code.class.getResourceAsStream("/ide/ui/darcula.xml")) {
            Theme.load(in, Zoom.code(13)).apply(area);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A touchpad's sideways swipe scrolls what shows the code sideways. On X11 (XToolkit, Java 25) it does not come as
     * a wheel but as presses of buttons 4 (left) and 5 (right), the X server's 6 and 7, Shift held or not; a wheel and
     * Shift+wheel come as wheels, which the scroll pane takes.
     */
    static <T extends javax.swing.JComponent> T sideways(T code) {
        code.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                if (event.getButton() != 4 && event.getButton() != 5) return;
                var pane = (javax.swing.JScrollPane) javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, code);
                if (pane == null) return;
                var bar = pane.getHorizontalScrollBar();
                bar.setValue(bar.getValue() + (event.getButton() == 4 ? -SWIPE : SWIPE));
                event.consume();
            }
        });
        return code;
    }

    /** How far a swipe event goes sideways, in pixels. */
    static final int SWIPE = 24;

    /** Marks a line (from 0) with a color under its text. */
    static void mark(RSyntaxTextArea area, int line, Color color) {
        try {
            area.addLineHighlight(line, color);
        } catch (BadLocationException nowhere) {
            // a line that is not there
        }
    }
}
