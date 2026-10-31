package ide.java.primitives;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ide.java.semantics.Bodies;
import ide.program.Change;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import static ide.java.semantics.Resolution.resolveField;

/**
 * Replaces a call to a method whose body is a single {@code return expr;} by that expression: each parameter becomes
 * its argument (parenthesized where precedence needs it), and so does the whole expression at the call site.
 * Refuses recursion, a body using implicit {@code this} members called on another receiver, and a non-trivial
 * argument whose parameter is not used exactly once (it would be evaluated another number of times).
 */
final class InlineCall {

    private InlineCall() {
    }

    static Change apply(Primitive.Inline inline, Context c, Program before) {
        var ref = before.reference(inline.reference());
        if (!(c.use(ref.id()) instanceof MethodCallExpr call)) {
            throw new PrimitiveRejected("inline.not-a-call", ref.id() + " is not a call");
        }
        var method = before.element(ref.target());
        var declaration = c.declaration(method).as(MethodDeclaration.class);
        var statements = declaration.getBody().map(b -> b.getStatements()).orElseThrow();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt() || statements.get(0).asReturnStmt().getExpression().isEmpty()) {
            throw new PrimitiveRejected("inline.body-shape", method.qualified() + " is not a single 'return expr;'");
        }
        var original = statements.get(0).asReturnStmt().getExpression().get(); // attached, so names resolve
        if (original.findAll(MethodCallExpr.class).stream().anyMatch(k -> method.id().equals(c.reader().resolveMethod(k)))) {
            throw new PrimitiveRejected("inline.recursive", method.qualified() + " calls itself");
        }
        var thisMembers = original.findAll(NameExpr.class).stream().anyMatch(n -> resolveField(n) != null)
                || original.findAll(MethodCallExpr.class).stream().anyMatch(k -> k.getScope().isEmpty());
        if (thisMembers && call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
            throw new PrimitiveRejected("inline.receiver", method.qualified() + " uses members of this; the call has another receiver");
        }
        var body = original.clone(); // a detached copy, rewritten and printed: the text the call becomes
        var params = declaration.getParameters();
        var args = call.getArguments();
        for (var i = 0; i < params.size(); i++) {
            var name = params.get(i).getNameAsString();
            var uses = body.findAll(NameExpr.class, n -> n.getNameAsString().equals(name));
            if (uses.size() != 1 && !Bodies.trivial(args.get(i))) {
                throw new PrimitiveRejected("inline.evaluation-count", args.get(i) + " would be evaluated " + uses.size() + " times");
            }
        }
        // substitute on the copy; replacing a name that is the whole body replaces the root
        Expression result = body;
        for (var i = 0; i < params.size(); i++) {
            var name = params.get(i).getNameAsString();
            for (var use : body.findAll(NameExpr.class, n -> n.getNameAsString().equals(name))) {
                var arg = args.get(i).clone();
                var replacement = Bodies.needsParentheses(arg, use) ? Bodies.parenthesized(arg) : arg;
                if (use == result) result = replacement;
                else use.replace(replacement);
            }
        }
        var text = Bodies.needsParentheses(result, call) ? "(" + result + ")" : result.toString();
        var unit = c.sources().unitOf(call);
        c.sources().apply(Shared.edits(e -> e.replace(unit, call, text)));
        return inline.intent(before, null);
    }
}
