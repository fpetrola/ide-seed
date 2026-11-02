package ide.java.semantics;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * The calls that only read: of a library method declared so ({@code reads.txt}), or of a getter of the workspace nothing
 * overrides. A call of one answers what its object holds and does nothing else; any other call has effects.
 */
public final class Reads {

    private static final Set<String> DECLARED = load();

    private Reads() {
    }

    /** Whether a call is of a library method declared to only read. */
    public static boolean declared(MethodCallExpr call) {
        try {
            return DECLARED.contains(call.resolve().getQualifiedSignature());
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    /**
     * Whether a call is of a method of the workspace whose body only answers a field of its object ({@code return
     * name;}), what it says itself; whether something overrides it is the program's to say.
     */
    public static boolean getter(MethodCallExpr call) {
        try {
            return call.resolve().toAst().filter(MethodDeclaration.class::isInstance).map(MethodDeclaration.class::cast)
                    .flatMap(MethodDeclaration::getBody).filter(body -> body.getStatements().size() == 1)
                    .map(body -> body.getStatement(0)).filter(Statement::isReturnStmt)
                    .flatMap(answer -> answer.asReturnStmt().getExpression())
                    .filter(value -> value.isNameExpr() && Resolution.resolveField(value.asNameExpr()) != null
                            || value.isFieldAccessExpr() && value.asFieldAccessExpr().getScope().isThisExpr())
                    .isPresent();
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    private static Set<String> load() {
        try (var in = Reads.class.getResourceAsStream("/ide/java/reads.txt")) {
            return Set.copyOf(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
