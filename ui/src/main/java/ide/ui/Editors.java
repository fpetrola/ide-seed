package ide.ui;

import org.fife.ui.rsyntaxtextarea.ErrorStrip;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The open files, a tab each: Java in its colors, the problems of the file underlined and marked beside it, and the
 * text last applied, so that what is typed and not applied yet is never overwritten.
 */
final class Editors extends JTabbedPane {

    /** A file's editor and the text it last applied. */
    private static final class Editor {

        final String path;
        final RSyntaxTextArea area = new RSyntaxTextArea(30, 100);
        final Problems problems = new Problems();
        String applied;

        Editor(String path, String text) {
            this.path = path;
            this.applied = text;
        }
    }

    /** The problems of a file, as notices the editor underlines. */
    private static final class Problems extends AbstractParser {

        private List<Map<String, Object>> problems = List.of();

        @Override
        public ParseResult parse(RSyntaxDocument document, String style) {
            var result = new DefaultParseResult(this);
            for (var problem : problems) {
                var message = String.valueOf(problem.get("message")).lines().findFirst().orElse("");
                result.addNotice(new DefaultParserNotice(this, message, ((Number) problem.get("line")).intValue() - 1));
            }
            return result;
        }
    }

    private final Map<String, Editor> editors = new LinkedHashMap<>();
    private final List<KeyStroke> keys;
    private float size = 14f;

    /** {@code keys} are the window's: the editors leave them to it. */
    Editors(List<KeyStroke> keys) {
        this.keys = keys;
    }

    /** Shows a file's tab, opening it with {@code text} if it is not open yet. */
    void open(String path, String text) {
        var editor = editors.get(path);
        if (editor == null) {
            editor = new Editor(path, text);
            var area = editor.area;
            area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
            area.setCodeFoldingEnabled(true);
            area.setAntiAliasingEnabled(true);
            theme(area);
            area.setText(text);
            area.setCaretPosition(0);
            area.discardAllEdits();
            area.addParser(editor.problems);
            keys.forEach(key -> area.getInputMap().put(key, "none"));
            var panel = new JPanel(new BorderLayout());
            panel.add(new RTextScrollPane(area), BorderLayout.CENTER);
            panel.add(new ErrorStrip(area), BorderLayout.LINE_END);
            editors.put(path, editor);
            addTab(path.substring(path.lastIndexOf('/') + 1), panel);
            setToolTipTextAt(getTabCount() - 1, path);
        }
        setSelectedIndex(List.copyOf(editors.keySet()).indexOf(path));
    }

    Optional<String> current() {
        var index = getSelectedIndex();
        return index < 0 ? Optional.empty() : Optional.of(List.copyOf(editors.keySet()).get(index));
    }

    List<String> open() {
        return List.copyOf(editors.keySet());
    }

    String text(String path) {
        return editor(path).area.getText();
    }

    /** What people typed, not applied yet. */
    void type(String path, String text) {
        editor(path).area.setText(text);
    }

    /** The files whose editors say something they did not apply yet, with what they say. */
    Map<String, String> unapplied() {
        var unapplied = new LinkedHashMap<String, String>();
        editors.values().stream().filter(e -> !e.area.getText().equals(e.applied)).forEach(e -> unapplied.put(e.path, e.area.getText()));
        return unapplied;
    }

    /** A file's text as the program has it now; an editor shows it unless it has something not applied. */
    void applied(String path, String text) {
        var editor = editors.get(path);
        if (editor == null) return;
        if (editor.area.getText().equals(editor.applied) && !text.equals(editor.applied)) {
            var caret = editor.area.getCaretPosition();
            editor.area.setText(text);
            editor.area.setCaretPosition(Math.min(caret, text.length()));
        }
        editor.applied = text;
    }

    /** The problems of the files, as the IDE lists them: path, line and message each. */
    void problems(List<Map<String, Object>> problems) {
        for (var editor : editors.values()) {
            editor.problems.problems = problems.stream().filter(p -> editor.path.equals(p.get("path"))).toList();
            editor.area.forceReparsing(editor.problems);
        }
    }

    /** The caret of the current editor, from 1. */
    int line() {
        return current().map(path -> editor(path).area.getCaretLineNumber() + 1).orElse(1);
    }

    int column() {
        return current().map(path -> editor(path).area.getCaretOffsetFromLineStart() + 1).orElse(1);
    }

    /** The caret of the current editor at a line and a column, from 1. */
    void caret(int line, int column) {
        current().ifPresent(path -> {
            var area = editor(path).area;
            try {
                var row = Math.max(0, Math.min(line, area.getLineCount()) - 1);
                var start = area.getLineStartOffset(row);
                area.setCaretPosition(Math.min(start + Math.max(0, column - 1), area.getLineEndOffset(row)));
            } catch (BadLocationException outside) {
                area.setCaretPosition(0);
            }
            area.requestFocusInWindow();
        });
    }

    /** The current editor's text and caret offset: what completion is asked at. */
    int offset() {
        return current().map(path -> editor(path).area.getCaretPosition()).orElse(0);
    }

    /** Writes {@code text} in place of the {@code length} characters before the caret. */
    void replaceBefore(int length, String text) {
        current().ifPresent(path -> {
            var area = editor(path).area;
            var end = area.getCaretPosition();
            area.replaceRange(text, Math.max(0, end - length), end);
        });
    }

    /** The name being written just before the caret. */
    String word() {
        var path = current();
        if (path.isEmpty()) return "";
        var text = text(path.get());
        var end = offset();
        var start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        return text.substring(start, end);
    }

    void zoom(float delta) {
        size = Math.max(8f, size + delta);
        editors.values().forEach(editor -> editor.area.setFont(editor.area.getFont().deriveFont(size)));
    }

    private void theme(RSyntaxTextArea area) {
        try (var in = Editors.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
            Theme.load(in).apply(area);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        area.setFont(area.getFont().deriveFont(size));
    }

    private Editor editor(String path) {
        var editor = editors.get(path);
        if (editor == null) throw new IllegalArgumentException("not open: " + path);
        return editor;
    }
}
