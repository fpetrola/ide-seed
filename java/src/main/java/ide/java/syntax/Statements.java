package ide.java.syntax;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.BlockStmt;
import ide.java.text.EditSet;
import ide.java.text.Unit;

/**
 * Statements of a body written as text: whether one has its lines to itself, and one put just before another or at the
 * end of a body, at the indentation of the statements there. A statement's own text and lines are a member's
 * ({@link Members#text}, {@link Members#remove}): its comment goes with it.
 */
public final class Statements {

    private Statements() {
    }

    /** Whether nothing else is written on a statement's lines. */
    public static boolean alone(Unit unit, Node statement) {
        return unit.lines(statement.getBegin().orElseThrow().line, statement.getEnd().orElseThrow().line).strip()
                .equals(unit.textOf(statement).strip());
    }

    /** A statement's text, just before {@code anchor}, at its indentation. */
    public static void insertBefore(EditSet edits, Unit unit, Node anchor, String statement) {
        var line = anchor.getComment().flatMap(Node::getBegin).orElse(anchor.getBegin().orElseThrow()).line;
        edits.insertAtLine(unit, line, unit.indentation(line) + statement + "\n");
    }

    /** A statement's text at the end of a body, at {@code indent}: after its last statement, or alone in it. */
    public static void append(EditSet edits, Unit unit, BlockStmt body, String indent, String statement) {
        var statements = body.getStatements();
        if (statements.isNonEmpty()) {
            edits.insertAtLine(unit, statements.getLast().orElseThrow().getEnd().orElseThrow().line + 1, indent + statement + "\n");
        } else {
            var closing = unit.indentation(body.getEnd().orElseThrow().line);
            edits.replace(unit, body, "{\n" + indent + statement + "\n" + closing + "}");
        }
    }
}
