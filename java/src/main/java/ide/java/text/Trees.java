package ide.java.text;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.util.LinkedHashMap;

/**
 * The trees in memory: the ones used last, while their texts add up to no more than it keeps (a tree's size goes with
 * its text's, not with how many there are); any other one lives only while something holds a node of it. A unit whose
 * tree was let go has it parsed again from its text.
 */
final class Trees {

    private final JavaParser parser;
    private final int keeping;
    private final LinkedHashMap<Unit, CompilationUnit> kept = new LinkedHashMap<>(16, 0.75f, true);
    private long size; // the length of the texts of the trees kept

    Trees(JavaParser parser, int keeping) {
        this.parser = parser;
        this.keeping = keeping;
    }

    ParseResult<CompilationUnit> parse(String text) {
        var result = parser.parse(text);
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
