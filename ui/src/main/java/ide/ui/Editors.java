package ide.ui;

import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.Completion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.ErrorStrip;
import org.fife.ui.rsyntaxtextarea.LinkGeneratorResult;
import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rsyntaxtextarea.parser.AbstractParser;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParseResult;
import org.fife.ui.rsyntaxtextarea.parser.DefaultParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParserNotice;
import org.fife.ui.rsyntaxtextarea.parser.ParseResult;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.JTextComponent;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The open files, a tab each, as IntelliJ shows them: Java in Darcula's colors and JetBrains Mono, the problems of the
 * file underlined and marked in the margin, the name under the caret marked wherever it occurs, completion in a popup
 * at the caret (Ctrl+Space, or after a dot), Ctrl+Click to go where a name leads. Each keeps the text it last
 * applied, so that what is typed and not applied yet is never overwritten.
 */
final class Editors extends JTabbedPane {

    /** What the editors ask the window: what may be written at a point, where a name leads, that the caret moved. */
    interface Help {

        /** Each a {@code label} and the {@code kind} of what it names. */
        List<Map<String, Object>> completions(String path, String text, int offset);

        /** Undoes the last change to the program, when the editor has nothing of its own to undo. */
        void undo();

        /** Going where the name at a line and column leads, if it leads anywhere. */
        Optional<Runnable> link(String path, int line, int column);

        void moved();
    }

    /** A file's editor and the text it last applied. */
    private static final class Editor {

        final String path;
        final RSyntaxTextArea area = new RSyntaxTextArea(30, 100);
        final Problems problems = new Problems();
        RTextScrollPane scroll;
        AutoCompletion completion;
        String applied;

        Editor(String path, String text) {
            this.path = path;
            this.applied = text;
        }
    }

    /** IntelliJ's colors for an error's wave and a warning's. */
    static final java.awt.Color ERROR = new java.awt.Color(0xF75464);
    static final java.awt.Color WARNING = new java.awt.Color(0xD9A343);

    /** The problems of a file, as notices the editor underlines where they are, red or yellow. */
    private static final class Problems extends AbstractParser {

        private List<Map<String, Object>> problems = List.of();

        @Override
        public ParseResult parse(RSyntaxDocument document, String style) {
            var result = new DefaultParseResult(this);
            var root = document.getDefaultRootElement();
            for (var problem : problems) {
                var message = String.valueOf(problem.get("message")).lines().findFirst().orElse("");
                var line = ((Number) problem.get("line")).intValue() - 1;
                var column = number(problem.get("column"));
                var length = number(problem.get("length"));
                DefaultParserNotice notice;
                if (line < root.getElementCount() && column > 0 && length > 0) {
                    var start = root.getElement(line).getStartOffset() + column - 1;
                    notice = new DefaultParserNotice(this, message, line, start, Math.min(length, document.getLength() - start));
                } else {
                    notice = new DefaultParserNotice(this, message, line);
                }
                var warning = "WARNING".equals(problem.get("severity"));
                notice.setLevel(warning ? ParserNotice.Level.WARNING : ParserNotice.Level.ERROR);
                notice.setColor(warning ? WARNING : ERROR);
                notice.setShowInEditor(true);
                result.addNotice(notice);
            }
            return result;
        }

        private static int number(Object value) {
            return value instanceof Number n ? n.intValue() : 0;
        }
    }

    private final Map<String, Editor> editors = new LinkedHashMap<>();
    /** The last problems the IDE said, which a file opened later shows too. */
    private List<Map<String, Object>> problems = List.of();
    private final List<KeyStroke> keys;
    private final Help help;

    /** {@code keys} are the window's: the editors leave them to it. */
    Editors(List<KeyStroke> keys, Help help) {
        this.keys = keys;
        this.help = help;
        addChangeListener(event -> help.moved());
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
            area.setMarkOccurrences(true);
            area.setMarkOccurrencesDelay(250);
            area.setTabSize(4);
            area.setHighlightCurrentLine(true);
            theme(area);
            area.setText(text);
            area.setCaretPosition(0);
            area.discardAllEdits();
            area.addParser(editor.problems);
            keys.forEach(key -> area.getInputMap().put(key, "none"));
            area.getInputMap().put(KeyStroke.getKeyStroke("ctrl Z"), "undo-typing-or-change");
            area.getActionMap().put("undo-typing-or-change", new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (area.canUndo()) area.undoLastAction();
                    else help.undo();
                }
            });
            area.setHyperlinksEnabled(true);
            area.setLinkScanningMask(InputEvent.CTRL_DOWN_MASK);
            area.setLinkGenerator((textArea, offset) -> link(path, textArea, offset));
            area.addCaretListener(event -> help.moved());
            editor.completion = completion(path, area);
            editor.scroll = new RTextScrollPane(area);
            editor.scroll.setIconRowHeaderEnabled(true);
            editor.scroll.getGutter().setLineNumberFont(Zoom.code(13));
            editor.scroll.setBorder(BorderFactory.createEmptyBorder());
            var panel = new JPanel(new BorderLayout());
            panel.add(editor.scroll, BorderLayout.CENTER);
            panel.add(new ErrorStrip(area), BorderLayout.LINE_END);
            editors.put(path, editor);
            addTab(path.substring(path.lastIndexOf('/') + 1), Icons.of("CLASS"), panel, path);
            problems(problems);
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
            editor.area.discardAllEdits(); // the program changed it: its undo is the program's, not the text's
        }
        editor.applied = text;
    }

    /** The problems of the files, as the IDE lists them (path, line and message each): underlined, and in the margin. */
    void problems(List<Map<String, Object>> problems) {
        this.problems = problems;
        for (var editor : editors.values()) {
            editor.problems.problems = problems.stream().filter(p -> editor.path.equals(p.get("path"))).toList();
            editor.area.forceReparsing(editor.problems);
            var gutter = editor.scroll.getGutter();
            gutter.removeAllTrackingIcons();
            for (var problem : editor.problems.problems) {
                try {
                    gutter.addLineTrackingIcon(((Number) problem.get("line")).intValue() - 1,
                            Icons.of("WARNING".equals(problem.get("severity")) ? "WARNING" : "ERROR"),
                            String.valueOf(problem.get("message")).lines().findFirst().orElse(""));
                } catch (BadLocationException outside) {
                    // a line the text no longer has
                }
            }
        }
    }

    /** Opens completion at the caret of the current editor. */
    void complete() {
        current().ifPresent(path -> editor(path).completion.doCompletion());
    }

    /** Where the caret is on the screen, just below its line; nowhere when the editor is not shown. */
    Point caretOnScreen() {
        var path = current();
        if (path.isEmpty()) return null;
        var area = editor(path.get()).area;
        if (!area.isShowing()) return null;
        try {
            var bounds = area.modelToView2D(area.getCaretPosition()).getBounds();
            var point = new Point(bounds.x, bounds.y + bounds.height + 2);
            SwingUtilities.convertPointToScreen(point, area);
            return point;
        } catch (BadLocationException outside) {
            return null;
        }
    }

    /** The name the caret is on, or just after. */
    String name() {
        var path = current();
        if (path.isEmpty()) return "";
        var text = text(path.get());
        var start = Math.min(offset(), text.length());
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        var end = Math.min(offset(), text.length());
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private LinkGeneratorResult link(String path, RSyntaxTextArea area, int offset) {
        try {
            var text = area.getText();
            var start = offset;
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            if (start >= text.length() || !Character.isJavaIdentifierStart(text.charAt(start))) return null;
            var line = area.getLineOfOffset(start);
            var go = help.link(path, line + 1, start - area.getLineStartOffset(line) + 1);
            if (go.isEmpty()) return null;
            var source = start;
            return new LinkGeneratorResult() {
                @Override
                public HyperlinkEvent execute() {
                    go.get().run();
                    return null;
                }

                @Override
                public int getSourceOffset() {
                    return source;
                }
            };
        } catch (BadLocationException outside) {
            return null;
        }
    }

    /** Completion in a popup at the caret, as IDEs offer it: on Ctrl+Space, and after a dot. */
    private AutoCompletion completion(String path, RSyntaxTextArea area) {
        var provider = new DefaultCompletionProvider() {
            @Override
            protected List<Completion> getCompletionsImpl(JTextComponent component) {
                var offered = new ArrayList<Completion>();
                for (var candidate : help.completions(path, component.getText(), component.getCaretPosition())) {
                    var completion = new BasicCompletion(this, String.valueOf(candidate.get("label")));
                    completion.setIcon(Icons.of(String.valueOf(candidate.get("kind"))));
                    offered.add(completion);
                }
                return offered;
            }
        };
        provider.setAutoActivationRules(false, ".");
        var completion = new AutoCompletion(provider);
        completion.setShowDescWindow(false);
        completion.setAutoCompleteSingleChoices(false);
        completion.setAutoActivationEnabled(true);
        completion.setAutoActivationDelay(200);
        completion.setTriggerKey(KeyStroke.getKeyStroke("ctrl SPACE"));
        completion.install(area);
        return completion;
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

    /** Writes the code at the current zoom. */
    void zoomed() {
        editors.values().forEach(editor -> {
            editor.area.setFont(Zoom.code(14));
            editor.scroll.getGutter().setLineNumberFont(Zoom.code(13));
        });
    }

    private void theme(RSyntaxTextArea area) {
        try (var in = Editors.class.getResourceAsStream("/ide/ui/darcula.xml")) {
            Theme.load(in, Zoom.code(14)).apply(area);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Editor editor(String path) {
        var editor = editors.get(path);
        if (editor == null) throw new IllegalArgumentException("not open: " + path);
        return editor;
    }
}
