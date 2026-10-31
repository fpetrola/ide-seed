package ide.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;

/** Import edits as minimal text changes; {@code cu} must be the parse of {@code code}. */
final class Imports {

    private Imports() {
    }

    /** After the last import, or in its own block after the package line. */
    static String add(String code, CompilationUnit cu, String qualified) {
        if (cu.getImports().isNonEmpty()) {
            var offset = Text.lineStart(code, cu.getImports().getLast().orElseThrow().getEnd().orElseThrow().line + 1);
            return code.substring(0, offset) + "import " + qualified + ";\n" + code.substring(offset);
        }
        var line = cu.getPackageDeclaration().map(p -> p.getEnd().orElseThrow().line).orElse(0);
        var offset = Text.lineStart(code, line + 1);
        return code.substring(0, offset) + "\nimport " + qualified + ";\n" + code.substring(offset);
    }

    static String replace(String code, ImportDeclaration declaration, String qualified) {
        return Text.replace(code, declaration, "import " + qualified + ";");
    }

    /** Removes the import's line, and the blank line after it when it was the only import. */
    static String remove(String code, CompilationUnit cu, ImportDeclaration declaration) {
        var first = declaration.getBegin().orElseThrow().line;
        var last = declaration.getEnd().orElseThrow().line;
        if (cu.getImports().size() == 1 && Text.blankLine(code, last + 1)) last++;
        return Text.removeLines(code, first, last);
    }
}
