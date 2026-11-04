package ide.java.text;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.util.LinkedHashMap;

/**
 * The trees in memory: the ones used last, while their texts add up to no more than it keeps (a tree's size goes with
 * its text's, not with how many there are); any other one lives only while something holds a node of it. A unit whose
 * tree was let go has it parsed again from its text.
 */
final class Trees {

    private final ParserConfiguration configuration;
    private final int keeping;
    private final LinkedHashMap<Unit, CompilationUnit> kept = new LinkedHashMap<>(16, 0.75f, true);
    private long size; // the length of the texts of the trees kept
    private final LinkedHashMap<Unit, org.eclipse.jdt.core.dom.CompilationUnit> doms = new LinkedHashMap<>(16, 0.75f, true);
    private long domSize; // the same, for Eclipse's DOM

    /** How Eclipse's parser reads a text: Java as the compiler takes it. */
    private static final java.util.Map<String, String> DOM_OPTIONS = domOptions();

    private static java.util.Map<String, String> domOptions() {
        var options = org.eclipse.jdt.core.JavaCore.getOptions();
        org.eclipse.jdt.core.JavaCore.setComplianceOptions("21", options);
        return options;
    }

    Trees(ParserConfiguration configuration, int keeping) {
        this.configuration = configuration;
        this.keeping = keeping;
    }

    ParseResult<CompilationUnit> parse(String text) {
        var result = new JavaParser(configuration).parse(text); // a parser keeps the last text's tokens and buffers
        result.getResult().ifPresent(Trees::withoutTokens);
        return result;
    }

    /** A unit's tree, read again from its text. */
    CompilationUnit again(Unit unit) {
        return parse(unit.text()).getResult().orElseThrow(() -> new IllegalStateException(unit.path() + " no longer parses"));
    }

    void used(Unit unit, CompilationUnit tree) {
        if (kept.put(unit, tree) == null) size += unit.text().length();
        var eldest = kept.entrySet().iterator();
        while (size > keeping && eldest.hasNext()) {
            size -= eldest.next().getKey().text().length();
            eldest.remove();
        }
    }

    void drop(Unit unit) {
        if (kept.remove(unit) != null) size -= unit.text().length();
        if (doms.remove(unit) != null) domSize -= unit.text().length();
    }

    /** A text read as Eclipse's DOM, with its comments; no names bound. */
    static org.eclipse.jdt.core.dom.CompilationUnit dom(String text) {
        var parser = org.eclipse.jdt.core.dom.ASTParser.newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest());
        parser.setKind(org.eclipse.jdt.core.dom.ASTParser.K_COMPILATION_UNIT);
        parser.setCompilerOptions(DOM_OPTIONS);
        parser.setSource(text.toCharArray());
        return (org.eclipse.jdt.core.dom.CompilationUnit) parser.createAST(null);
    }

    void usedDom(Unit unit, org.eclipse.jdt.core.dom.CompilationUnit tree) {
        if (doms.put(unit, tree) == null) domSize += unit.text().length();
        var eldest = doms.entrySet().iterator();
        while (domSize > keeping && eldest.hasNext()) {
            domSize -= eldest.next().getKey().text().length();
            eldest.remove();
        }
    }

    /** Each node and comment keeps its range; the tokens, kept by any of them, would hold as much memory as the tree. */
    private static void withoutTokens(CompilationUnit tree) {
        tree.walk(Trees::forgetTokens);
        tree.getAllComments().forEach(Trees::forgetTokens);
    }

    private static void forgetTokens(Node node) {
        node.getRange().ifPresent(range -> node.setTokenRange(null).setRange(range));
    }
}
