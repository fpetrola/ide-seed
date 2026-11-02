package ide.java.primitives;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import ide.java.semantics.Bodies;
import ide.java.syntax.Members;
import ide.java.text.EditSet;
import ide.program.Address;
import ide.program.Change;
import ide.program.ElementId;
import ide.program.Identity;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.stream.Collectors;

/**
 * Moves the statements of a method on lines {@code first-last}, or the expression that is exactly the stretch
 * {@code line:column-line:column}, into a new private method that the method calls:
 * inputs become parameters, at most one value used afterwards becomes the result. A fragment that ends the method with
 * its last return answers what the method answers, and the method returns what it answers. Refuses fragments that
 * are not whole statements of one block, that jump out otherwise, or that have more than one output. The change also says which uses
 * the new method makes now, and which uses of the method's locals are gone: the new method has parameters for them, or a
 * local of its own for what it answers. The locals declared only in the fragment move into the new method.
 */
final class ExtractFragment {

    private static final java.util.regex.Pattern STRETCH = java.util.regex.Pattern.compile("(\\d+):(\\d+)-(\\d+):(\\d+)");

    private ExtractFragment() {
    }

    /** An expression into a method that answers it (or just evaluates it, if it answers nothing), called in its place. */
    private static Change expression(Primitive.Extract extract, Context c, Program before, ide.program.Element source,
                                     ide.program.Element owner, ide.java.syntax.Decl declaration, MethodDeclaration method,
                                     java.util.regex.Matcher stretch) {
        var name = extract.name();
        var begin = new com.github.javaparser.Position(Integer.parseInt(stretch.group(1)), Integer.parseInt(stretch.group(2)));
        var end = new com.github.javaparser.Position(Integer.parseInt(stretch.group(3)), Integer.parseInt(stretch.group(4)));
        var expression = method.findAll(com.github.javaparser.ast.expr.Expression.class,
                        e -> e.getBegin().filter(begin::equals).isPresent() && e.getEnd().filter(end::equals).isPresent())
                .stream().findFirst().orElseThrow(() -> new PrimitiveRejected("extract.fragment", extract.fragment() + " is not an expression"));
        var writes = !expression.findAll(com.github.javaparser.ast.expr.AssignExpr.class).isEmpty()
                || !expression.findAll(com.github.javaparser.ast.expr.UnaryExpr.class, u -> u.getOperator().name().endsWith("CREMENT")).isEmpty();
        if (writes) throw new PrimitiveRejected("extract.writes", "'" + expression + "' changes a variable");
        var inputs = Bodies.inputs(method, expression);
        for (var input : inputs.values()) {
            if (input.isVarType()) throw new PrimitiveRejected("extract.var-type", "cannot name the type of a 'var' input");
        }
        var unit = declaration.unit();
        String type;
        try {
            type = expression.calculateResolvedType().isVoid() ? "void" : ide.java.semantics.UseNeeds.written(expression.calculateResolvedType(), unit.tree());
        } catch (RuntimeException unknown) {
            throw new PrimitiveRejected("extract.type", "cannot tell the type of '" + expression + "'");
        }
        var params = inputs.entrySet().stream().map(e -> e.getValue().asString() + " " + e.getKey()).collect(Collectors.joining(", "));
        var locals = Locals.of(c, before, source.id(), method, expression.getRange().orElseThrow(), List.of(), null);
        var typeDeclaration = c.declaration(owner).as(TypeDeclaration.class);
        var indent = Members.indentation(unit, typeDeclaration);
        var modifiers = method.isStatic() ? "private static " : "private ";
        var body = indent + "    " + (type.equals("void") ? "" : "return ") + unit.textOf(expression) + ";\n";
        var edits = new EditSet();
        edits.replace(unit, expression, name + "(" + String.join(", ", inputs.keySet()) + ")");
        Members.append(edits, unit, typeDeclaration, modifiers + type + " " + name + "(" + params + ") {\n" + body + indent + "}");
        c.sources().apply(edits);
        return created(extract, c, before, source, owner, unit, name, locals, null);
    }

    /** What an extraction made: the new method, which the uses moved into it are made from now. */
    private static Change created(Primitive.Extract extract, Context c, Program before, ide.program.Element source,
                                  ide.program.Element owner, ide.java.text.Unit unit, String name, Locals locals,
                                  Statements statements) {
        var created = Shared.typeIn(c.sources().unit(unit.path()), owner.qualified()).getMethodsByName(name).getFirst();
        var id = c.ids().of(new Address.Callable(owner.id(), name, c.keys().of(created)), Kind.METHOD);
        locals.move(c.ids(), source.id(), id);
        if (statements != null) statements.move(c.ids(), source.id(), id);
        var change = extract.intent(before, id);
        for (var gone : locals.gone()) change = change.expect(gone);
        for (var moved : c.program().referencesFrom(id)) { // the fragment's uses are made from the new method
            change = change.expect(new Change.Resited(moved.target(), source.id(), id));
        }
        return change;
    }

    static Change apply(Primitive.Extract extract, Context c, Program before) {
        var name = extract.name();
        Shared.requireIdentifier(name);
        var source = before.element(extract.method());
        var owner = before.element(source.parent());
        var declaration = c.declaration(source);
        var method = declaration.as(MethodDeclaration.class);
        var stretch = STRETCH.matcher(extract.fragment().strip());
        if (stretch.matches()) return expression(extract, c, before, source, owner, declaration, method, stretch);
        var selected = extract.fragment().contains(Kind.STATEMENT.prefix() + ":") ? named(c, before, source, method, extract.fragment())
                : lines(method, extract.fragment());
        if (selected.isEmpty()) {
            throw new PrimitiveRejected("extract.fragment", extract.fragment() + " is not whole statements of one block");
        }
        var ending = selected.getLast();
        var returning = ending instanceof ReturnStmt answer && answer.getExpression().isPresent()
                && method.getBody().flatMap(b -> b.getStatements().getLast()).filter(s -> s == ending).isPresent();
        var jump = Bodies.jumpOut(selected, returning);
        if (jump != null) throw new PrimitiveRejected("extract.jump", "'" + jump + "' leaves the fragment");
        var flow = Bodies.flow(method, selected);
        if (flow.outputs().size() > 1) throw new PrimitiveRejected("extract.outputs", "more than one value is used afterwards: " + flow.outputs());
        for (var input : flow.inputs().values()) {
            if (input.isVarType()) throw new PrimitiveRejected("extract.var-type", "cannot name the type of a 'var' input");
        }
        var output = flow.outputs().isEmpty() ? null : flow.outputs().keySet().iterator().next();
        var outputType = returning ? method.getType().asString() : output == null ? "void" : flow.outputs().get(output).asString();
        var params = flow.inputs().entrySet().stream().map(e -> e.getValue().asString() + " " + e.getKey()).collect(Collectors.joining(", "));

        var unit = declaration.unit();
        var type = c.declaration(owner).as(TypeDeclaration.class);
        var indent = Members.indentation(unit, type);
        var begin = selected.getFirst().getBegin().orElseThrow().line;
        var end = selected.getLast().getEnd().orElseThrow().line;
        var body = Bodies.reindent(unit.lines(begin, end), indent + "    ");
        if (output != null) body += indent + "    return " + output + ";\n";
        var locals = Locals.of(c, before, source.id(), method, new Range(selected.getFirst().getBegin().orElseThrow(),
                selected.getLast().getEnd().orElseThrow()), selected, output != null && flow.declaredInside(output) ? output : null);
        var at = Bodies.place(Bodies.statements(method), selected.getFirst());
        var statements = at < 0 ? null : new Statements(before.children(source.id()).stream()
                .filter(e -> e.kind() == Kind.STATEMENT).map(ide.program.Element::id).toList(), at, at + selected.size(), output != null);
        var call = name + "(" + String.join(", ", flow.inputs().keySet()) + ")";
        var callLine = returning ? "return " + call + ";" : output == null ? call + ";"
                : flow.declaredInside(output) ? outputType + " " + output + " = " + call + ";"
                : output + " = " + call + ";";
        var modifiers = method.isStatic() ? "private static " : "private ";
        var edits = new EditSet().replaceLines(unit, begin, end, unit.indentation(begin) + callLine + "\n");
        Members.append(edits, unit, type, modifiers + outputType + " " + name + "(" + params + ") {\n" + body + indent + "}");
        c.sources().apply(edits);
        return created(extract, c, before, source, owner, unit, name, locals, statements);
    }

    /** The statements on lines {@code first-last}, or on one line, whole statements of one block; none if they are not. */
    private static List<Statement> lines(MethodDeclaration method, String fragment) {
        var dash = fragment.indexOf('-');
        var first = Integer.parseInt((dash < 0 ? fragment : fragment.substring(0, dash)).strip());
        return Bodies.statementsWithin(method, first, dash < 0 ? first : Integer.parseInt(fragment.substring(dash + 1).strip()));
    }

    /** The statements the fragment names by their ids: statements of the method's body that follow one another. */
    private static List<Statement> named(Context c, Program before, ide.program.Element source, MethodDeclaration method, String fragment) {
        var body = Bodies.statements(method);
        var named = new ArrayList<Statement>();
        for (var id : fragment.split(",")) {
            var element = new ElementId(id.strip());
            if (!before.contains(element) || !source.id().equals(before.element(element).parent())) return List.of();
            var statement = c.declaration(before.element(element)).as(Statement.class);
            if (!named.isEmpty() && Bodies.place(body, statement) != Bodies.place(body, named.getLast()) + 1) return List.of();
            named.add(statement);
        }
        return named;
    }

    /**
     * The method's statements, when the fragment is some of them ({@code from} to {@code to}, not included): they move
     * into the new method, where a new return of what it answers may follow them, and the call takes their place.
     */
    private record Statements(List<ElementId> own, int from, int to, boolean answers) {

        /** Identity follows, before anything is read again: those that went first, so that those left close ranks. */
        void move(Identity ids, ElementId source, ElementId method) {
            var moved = new ArrayList<ElementId>(own.subList(from, to));
            if (answers) moved.add(null);
            ids.placeStatements(method, moved);
            var left = new ArrayList<ElementId>(own.subList(0, from));
            left.add(null);
            left.addAll(own.subList(to, own.size()));
            ids.placeStatements(source, left);
        }
    }

    /**
     * What an extraction does to the method's locals. Those declared in the fragment move into the new method, all but
     * the one it answers, which stays where the call declares it again; the fragment's uses of the others are gone.
     * {@code moving} and {@code staying} are the locals of the new method and of this one, in the order they are written.
     */
    private record Locals(SequencedMap<ElementId, String> moving, SequencedMap<ElementId, String> staying,
                          List<Change.Expectation> gone) {

        /** {@code statements} are the fragment's own (none for an expression), {@code answered} the output it declares. */
        static Locals of(Context c, Program before, ElementId source, MethodDeclaration method,
                         Range fragment, List<Statement> statements, String answered) {
            var moving = new LinkedHashMap<ElementId, String>();
            var staying = new LinkedHashMap<ElementId, String>();
            for (var local : Bodies.locals(method)) {
                var inside = local.getRange().filter(fragment::contains).isPresent();
                (inside && !answers(local, statements, answered) ? moving : staying).put(c.reader().local(local), local.getNameAsString());
            }
            var gone = new ArrayList<Change.Expectation>();
            for (var use : before.referencesFrom(source)) {
                if (before.element(use.target()).kind() == Kind.LOCAL && !moving.containsKey(use.target())
                        && c.use(use.id()).getRange().filter(fragment::contains).isPresent()) {
                    gone.add(new Change.Dropped(source, use.target()));
                }
            }
            return new Locals(moving, staying, gone);
        }

        /** Whether a local is the output the fragment declares at its top, which the call declares again. */
        private static boolean answers(VariableDeclarator local, List<Statement> statements, String answered) {
            var statement = local.getParentNode().flatMap(Node::getParentNode).orElse(null);
            return local.getNameAsString().equals(answered) && statements.stream().anyMatch(s -> s == statement);
        }

        /**
         * Identity follows, before anything is read again: those that went first, so that those left close ranks. The
         * local the new method answers is the last of its name there, and new.
         */
        void move(Identity ids, ElementId source, ElementId method) {
            ids.placeLocals(method, moving);
            ids.placeLocals(source, staying);
        }
    }
}
