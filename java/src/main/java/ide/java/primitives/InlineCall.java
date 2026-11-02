package ide.java.primitives;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import ide.java.semantics.Bodies;
import ide.program.Change;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import static ide.java.semantics.Resolution.resolveField;

/**
 * Replaces a call to a method whose body is a single {@code return expr;} by that expression, or a call made as a
 * statement to a method of statements by them (see {@link #statements}): each parameter becomes
 * its argument (parenthesized where precedence needs it), and so does the whole expression at the call site.
 * Refuses recursion, a body using implicit {@code this} members called on another receiver, and a non-trivial
 * argument whose parameter is not used exactly once (it would be evaluated another number of times). A read of a local
 * is inlined by {@link InlineLocal}.
 */
final class InlineCall {

    private InlineCall() {
    }

    static Change apply(Primitive.Inline inline, Context c, Program before) {
        var ref = before.reference(inline.reference());
        if (before.element(ref.target()).kind() == Kind.LOCAL) return InlineLocal.apply(inline, c, before, ref);
        if (c.use(ref.id()) instanceof com.github.javaparser.ast.expr.MethodReferenceExpr reference) return reference(inline, c, before, ref, reference);
        if (!(c.use(ref.id()) instanceof MethodCallExpr call)) {
            throw new PrimitiveRejected("inline.not-a-call", ref.id() + " is not a call");
        }
        return call(inline, c, before, call, before.element(ref.target()), ref.site());
    }

    /**
     * A method reference is first the lambda that calls the method ({@code Rows::processRow} → {@code (row) ->
     * processRow(row)}), whose call is then inlined. Only a reference through the method's type, to a static method,
     * or through {@code this}: one through another object would have it taken once, when the reference is made. A
     * type the method is in, named in the reference, is named no more.
     */
    private static Change reference(Primitive.Inline inline, Context c, Program before, ide.program.Reference ref,
                                    com.github.javaparser.ast.expr.MethodReferenceExpr reference) {
        var method = before.element(ref.target());
        var declaration = c.declaration(method).as(MethodDeclaration.class);
        var scope = reference.getScope();
        var statik = declaration.isStatic();
        if (!(scope.isThisExpr() && !statik || scope.isTypeExpr() && statik)) {
            throw new PrimitiveRejected("inline.reference", reference + " is made through an object of its own, or needs one");
        }
        var caller = reference.findAncestor(com.github.javaparser.ast.body.CallableDeclaration.class).orElseThrow();
        var there = new java.util.HashSet<String>();
        Bodies.locals(caller).forEach(v -> there.add(v.getNameAsString()));
        ((com.github.javaparser.ast.body.CallableDeclaration<?>) caller).getParameters().forEach(p -> there.add(p.getNameAsString()));
        var names = new java.util.ArrayList<String>();
        for (var param : declaration.getParameters()) { // a lambda's parameter may not hide a local of where it is
            var name = param.getNameAsString();
            for (var n = 2; there.contains(name) || names.contains(name); n++) name = param.getNameAsString() + n;
            names.add(name);
        }
        var own = caller.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class).orElseThrow() == declaration.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class).orElseThrow();
        var unit = c.sources().unitOf(reference);
        var through = scope.isTypeExpr() && !own ? unit.textOf(scope) + "." : "";
        var arguments = String.join(", ", names);
        var begin = reference.getBegin().orElseThrow();
        c.sources().apply(Shared.edits(e -> e.replace(unit, reference, "(" + arguments + ") -> " + through + method.name() + "(" + arguments + ")")));
        var lambda = c.sources().unit(unit.path()).tree().findFirst(com.github.javaparser.ast.expr.LambdaExpr.class,
                l -> l.getBegin().filter(begin::equals).isPresent()).orElseThrow();
        var call = lambda.getBody().findFirst(MethodCallExpr.class).orElseThrow();
        var change = call(inline, c, before, call, method, ref.site());
        return scope.isTypeExpr() && own ? change.expect(new Change.Dropped(ref.site(), method.parent())) : change; // its type named no more there
    }

    /** A call of {@code method}, made from {@code site}, replaced by what the method does. */
    private static Change call(Primitive.Inline inline, Context c, Program before, MethodCallExpr call, ide.program.Element method,
                               ide.program.ElementId site) {
        var declaration = c.declaration(method).as(MethodDeclaration.class);
        var statements = declaration.getBody().map(b -> b.getStatements()).orElseThrow();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt() || statements.get(0).asReturnStmt().getExpression().isEmpty()) {
            return statements(inline, c, before, call, method, declaration, site);
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

    /**
     * A call made as a statement (or as a lambda's body) of a method of statements: its statements take the call's
     * place (or become the lambda's block), each parameter its argument. Refused: a return anywhere in them (it would
     * leave the caller), a parameter given a value, an argument that does something used other than once, members of
     * this called on another receiver. A local of theirs named as one where the call is takes a name of its own, the
     * first {@code name2}, {@code name3}... free there (the verifier sees a field it would hide).
     */
    private static Change statements(Primitive.Inline inline, Context c, Program before, MethodCallExpr call,
                                     ide.program.Element method, MethodDeclaration declaration, ide.program.ElementId site) {
        var parent = call.getParentNode().orElseThrow();
        var lambda = parent instanceof com.github.javaparser.ast.stmt.ExpressionStmt wrapped // a lambda's body as an expression
                && wrapped.getParentNode().orElse(null) instanceof com.github.javaparser.ast.expr.LambdaExpr l && l.getExpressionBody().filter(call::equals).isPresent();
        var statement = parent instanceof com.github.javaparser.ast.stmt.ExpressionStmt e && e.getParentNode().orElse(null) instanceof com.github.javaparser.ast.stmt.BlockStmt;
        var body = declaration.getBody().orElseThrow();
        if (!lambda && !statement) throw new PrimitiveRejected("inline.body-shape", method.qualified() + " is made of statements; its call is not one");
        if (!body.findAll(com.github.javaparser.ast.stmt.ReturnStmt.class, r -> r.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class)
                .filter(body::isAncestorOf).isEmpty()).isEmpty()) {
            throw new PrimitiveRejected("inline.body-shape", method.qualified() + " returns from inside its statements");
        }
        if (body.findAll(MethodCallExpr.class).stream().anyMatch(k -> method.id().equals(c.reader().resolveMethod(k)))) {
            throw new PrimitiveRejected("inline.recursive", method.qualified() + " calls itself");
        }
        var thisMembers = body.findAll(NameExpr.class).stream().anyMatch(n -> resolveField(n) != null)
                || body.findAll(MethodCallExpr.class).stream().anyMatch(k -> k.getScope().isEmpty());
        if (thisMembers && call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
            throw new PrimitiveRejected("inline.receiver", method.qualified() + " uses members of this; the call has another receiver");
        }
        var params = declaration.getParameters();
        var args = call.getArguments();
        for (var param : params) {
            var name = param.getNameAsString();
            if (body.findAll(com.github.javaparser.ast.expr.AssignExpr.class).stream().anyMatch(a -> a.getTarget().isNameExpr()
                    && a.getTarget().asNameExpr().getNameAsString().equals(name))) {
                throw new PrimitiveRejected("inline.body-shape", method.qualified() + " gives its parameter " + name + " another value");
            }
        }
        var caller = call.findAncestor(com.github.javaparser.ast.body.CallableDeclaration.class).orElseThrow();
        var there = new java.util.HashSet<String>();
        Bodies.locals(caller).forEach(v -> there.add(v.getNameAsString()));
        ((com.github.javaparser.ast.body.CallableDeclaration<?>) caller).getParameters().forEach(p -> there.add(p.getNameAsString()));
        call.findAncestor(com.github.javaparser.ast.expr.LambdaExpr.class).ifPresent(l -> l.getParameters().forEach(p -> there.add(p.getNameAsString())));
        var copy = body.clone();
        var theirs = new java.util.HashSet<String>();
        copy.findAll(com.github.javaparser.ast.body.VariableDeclarator.class).forEach(v -> theirs.add(v.getNameAsString()));
        declaration.getParameters().forEach(p -> theirs.add(p.getNameAsString())); // they are replaced by name after
        for (var local : copy.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) { // one named as the caller's takes a name of its own
            var name = local.getNameAsString();
            if (!there.contains(name)) continue;
            var n = 2;
            while (there.contains(name + n) || theirs.contains(name + n)) n++;
            var renamed = name + n;
            theirs.add(renamed);
            copy.findAll(NameExpr.class, u -> u.getNameAsString().equals(name)).forEach(u -> u.setName(renamed));
            local.setName(renamed);
        }
        for (var i = 0; i < params.size(); i++) {
            var name = params.get(i).getNameAsString();
            var uses = copy.findAll(NameExpr.class, n -> n.getNameAsString().equals(name));
            if (uses.size() != 1 && !Bodies.trivial(args.get(i))) {
                throw new PrimitiveRejected("inline.evaluation-count", args.get(i) + " would be evaluated " + uses.size() + " times");
            }
            for (var use : uses) {
                var arg = args.get(i).clone();
                use.replace(Bodies.needsParentheses(arg, use) ? Bodies.parenthesized(arg) : arg);
            }
        }
        var unit = c.sources().unitOf(call);
        var indent = unit.indentation(call.getBegin().orElseThrow().line);
        var lines = new java.util.ArrayList<String>();
        for (var one : copy.getStatements()) {
            if (one.isReturnStmt()) continue;
            one.toString().lines().forEach(line -> lines.add(line));
        }
        if (statement) {
            var callStatement = (com.github.javaparser.ast.stmt.ExpressionStmt) parent;
            var own = before.children(site).stream().filter(e -> e.kind() == Kind.STATEMENT).map(ide.program.Element::id).toList();
            var place = Bodies.place(Bodies.statements(caller), callStatement);
            var text = new StringBuilder();
            for (var i = 0; i < lines.size(); i++) text.append(i == 0 ? "" : "\n" + indent).append(lines.get(i));
            c.sources().apply(Shared.edits(e -> e.replace(unit, callStatement, text.toString())));
            if (place >= 0) { // its statements take the call's place, new ones; those after it keep who they are
                var placed = new java.util.ArrayList<ide.program.ElementId>(own.subList(0, place));
                for (var one : copy.getStatements()) if (!one.isReturnStmt()) placed.add(null);
                placed.addAll(own.subList(place + 1, own.size()));
                c.ids().forget(own.get(place));
                c.ids().placeStatements(site, placed);
            }
        } else {
            var inner = indent + "    ";
            var text = "{\n" + String.join("\n", lines.stream().map(line -> inner + line).toList()) + "\n" + indent + "}";
            c.sources().apply(Shared.edits(e -> e.replace(unit, call, text)));
        }
        return inline.intent(before, null);
    }
}
