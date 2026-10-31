package ide.java;

import com.github.javaparser.ast.Node;

/** Text edits addressed by AST positions (1-based lines and columns, end inclusive). */
final class Text {

    private Text() {
    }

    static int lineStart(String code, int line) {
        var offset = 0;
        for (var i = 1; i < line; i++) offset = code.indexOf('\n', offset) + 1;
        return offset;
    }

    static String replace(String code, Node node, String text) {
        var range = node.getRange().orElseThrow();
        var start = lineStart(code, range.begin.line) + range.begin.column - 1;
        var end = lineStart(code, range.end.line) + range.end.column;
        return code.substring(0, start) + text + code.substring(end);
    }

    /** Removes whole lines {@code first..last} (1-based, inclusive). */
    static String removeLines(String code, int first, int last) {
        return code.substring(0, lineStart(code, first)) + code.substring(lineStart(code, last + 1));
    }

    static boolean blankLine(String code, int line) {
        var start = lineStart(code, line);
        var end = code.indexOf('\n', start);
        return start < code.length() && code.substring(start, end < 0 ? code.length() : end).isBlank();
    }

    /**
     * After removing lines so that {@code line} is the first one after the gap: drops one blank line if the gap
     * left two blank lines together, or a blank line right after an opening or before a closing brace.
     */
    static String collapseBlankAt(String code, int line) {
        var before = line > 1 ? lineText(code, line - 1).strip() : null;
        var after = lineText(code, line).strip();
        if (before != null && before.isEmpty() && (after.isEmpty() || after.startsWith("}"))) return removeLines(code, line - 1, line - 1);
        if (after.isEmpty() && before != null && before.endsWith("{")) return removeLines(code, line, line);
        return code;
    }

    private static String lineText(String code, int line) {
        var start = lineStart(code, line);
        if (start >= code.length()) return "";
        var end = code.indexOf('\n', start);
        return code.substring(start, end < 0 ? code.length() : end);
    }
}
