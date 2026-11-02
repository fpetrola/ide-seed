package ide.java.primitives;

import com.github.javaparser.ast.body.MethodDeclaration;
import ide.java.syntax.Imports;
import ide.java.syntax.Render;
import ide.java.text.EditSet;
import ide.java.text.Unit;
import ide.program.Body;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Program;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A statement in no language ({@link Body}) written in Java, at the end of a method's body or, calling a constructor,
 * at its start; and a field's start. A field or a parameter by its name, a field through {@code this} (or through its
 * type, if static) where a parameter of the same name hides it, and a new object by its type's simple name, imported
 * if the file cannot name it yet. The default is the one of the method's type, as a field would start with it; a void
 * method answers nothing, and a call it would answer is a statement.
 */
final class WriteBody {

    private final Program program;
    private final List<String> parameters;
    private final EditSet edits;
    private final Unit unit;
    private final Map<String, String> named = new HashMap<>();
    private final String type;
    private final ElementId owner;

    private WriteBody(Program program, List<String> parameters, EditSet edits, Unit unit, String type, ElementId owner) {
        this.program = program;
        this.parameters = parameters;
        this.edits = edits;
        this.unit = unit;
        this.type = type;
        this.owner = owner;
    }

    /** One statement said in no language, written at the end of a method's or constructor's body, or at its start. */
    static void append(Context c, ElementId method, Body.Statement said, boolean first) {
        var program = c.program();
        var declaration = c.declaration(program.element(method));
        com.github.javaparser.ast.body.CallableDeclaration<?> callable = declaration.as(com.github.javaparser.ast.body.CallableDeclaration.class);
        var unit = declaration.unit();
        var edits = new EditSet();
        var type = callable instanceof MethodDeclaration m ? m.getType().asString() : "void";
        var writer = new WriteBody(program, Shared.parameterNames(callable), edits, unit, type, program.element(method).parent());
        var statements = ide.java.semantics.Bodies.statements(callable);
        var indent = statements.isEmpty() ? unit.indentation(callable.getBegin().orElseThrow().line) + "    "
                : unit.indentation(statements.getFirst().getBegin().orElseThrow().line);
        if (first && !statements.isEmpty()) ide.java.syntax.Statements.insertBefore(edits, unit, statements.getFirst(), writer.statement(said));
        else ide.java.syntax.Statements.append(edits, unit, ide.java.semantics.Bodies.body(callable), indent, writer.statement(said));
        c.sources().apply(edits);
    }

    /**
     * One statement said in no language at the end of a body or of the block a statement of it holds (a loop's, or a
     * block itself), answering its id. Each over a local writes the loop that declares it: the local's own declaration,
     * alone at the start of the body where it was created, goes.
     */
    static ElementId into(Context c, Program before, ElementId parent, Body.Statement said) {
        var method = before.memberOf(parent);
        var declaration = c.declaration(method);
        com.github.javaparser.ast.body.CallableDeclaration<?> callable = declaration.as(com.github.javaparser.ast.body.CallableDeclaration.class);
        var unit = declaration.unit();
        var holder = parent.equals(method.id()) ? null : c.declaration(before.element(parent)).node();
        var block = holder == null ? ide.java.semantics.Bodies.body(callable)
                : holder instanceof com.github.javaparser.ast.stmt.BlockStmt b ? b
                : holder instanceof com.github.javaparser.ast.nodeTypes.NodeWithBody<?> loop && loop.getBody() instanceof com.github.javaparser.ast.stmt.BlockStmt b ? b : null;
        if (block == null) throw new ide.projection.PrimitiveRejected("create.statement", before.element(parent).qualified() + " holds no one block to end");
        var edits = new EditSet();
        var type = callable instanceof MethodDeclaration m ? m.getType().asString() : "void";
        var writer = new WriteBody(c.program(), Shared.parameterNames(callable), edits, unit, type, method.parent());
        var statements = block.getStatements();
        var indent = statements.isNonEmpty() ? unit.indentation(statements.getFirst().orElseThrow().getBegin().orElseThrow().line)
                : unit.indentation(block.getBegin().orElseThrow().line) + "    ";
        ElementId declared = null; // the statement that declared the local a loop declares now
        var own = new java.util.ArrayList<>(before.children(parent).stream().filter(e -> e.kind() == ide.program.Kind.STATEMENT).map(ide.program.Element::id).toList());
        String text;
        if (said instanceof Body.Each each) {
            var local = c.declaration(before.element(each.local())).as(com.github.javaparser.ast.body.VariableDeclarator.class);
            var alone = local.getParentNode().filter(d -> d instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr v && v.getVariables().size() == 1)
                    .flatMap(com.github.javaparser.ast.Node::getParentNode).filter(com.github.javaparser.ast.stmt.ExpressionStmt.class::isInstance).orElse(null);
            var top = ide.java.semantics.Bodies.statements(callable);
            var place = alone == null ? -1 : ide.java.semantics.Bodies.place(top, alone);
            if (place < 0 || local.getInitializer().isPresent()) {
                throw new ide.projection.PrimitiveRejected("create.statement", before.element(each.local()).qualified() + " is not declared alone, with no value, in its body: a loop cannot declare it");
            }
            declared = before.children(method.id()).stream().filter(e -> e.kind() == ide.program.Kind.STATEMENT).toList().get(place).id();
            text = "for (" + unit.textOf(((com.github.javaparser.ast.expr.VariableDeclarationExpr) local.getParentNode().orElseThrow()).getElementType())
                    + " " + local.getNameAsString() + " : " + writer.value(each.list()) + ") {\n" + indent + "}";
            ide.java.syntax.Members.remove(edits, unit, alone);
        } else {
            text = writer.statement(said);
        }
        ide.java.syntax.Statements.append(edits, unit, block, indent, text);
        c.sources().apply(edits);
        if (declared != null) {
            own.remove(declared);
            if (!parent.equals(method.id())) {
                var others = new java.util.ArrayList<>(before.children(method.id()).stream().filter(e -> e.kind() == ide.program.Kind.STATEMENT).map(ide.program.Element::id).toList());
                others.remove(declared);
                c.ids().forget(declared);
                c.ids().placeStatements(method.id(), others);
            } else {
                c.ids().forget(declared);
            }
        }
        own.add(null);
        c.ids().placeStatements(parent, own);
        return c.ids().of(new ide.program.Address.Statement(parent, own.size() - 1), ide.program.Kind.STATEMENT);
    }

    /** A value said in no language, as the file of {@code site} writes it: a field's start. */
    static String value(Context c, ElementId site, EditSet edits, Unit unit, String text) {
        return new WriteBody(c.program(), List.of(), edits, unit, "void", c.program().element(site).parent()).value(Body.value(text, c.program(), site));
    }

    private String statement(Body.Statement statement) {
        return switch (statement) {
            case Body.Return r when r.value() instanceof Body.Default && type.equals("void") -> "";
            case Body.Return r when r.value() instanceof Body.Call && type.equals("void") -> value(r.value()) + ";";
            case Body.Return r -> Render.returning(value(r.value()));
            case Body.Assign a -> Render.assignment(field(a.field()), value(a.value()));
            case Body.Construct made -> (program.element(made.constructor()).parent().equals(owner) ? "this" : "super")
                    + "(" + String.join(", ", made.arguments().stream().map(this::value).toList()) + ");";
            case Body.Add add -> value(add.list()) + ".add(" + value(add.value()) + ");";
            case Body.Each each -> throw new ide.projection.PrimitiveRejected("create.statement", "a loop is made where its local is: alone"); // see into
        };
    }

    private String value(Body.Expression expression) {
        return switch (expression) {
            case Body.Field f -> field(f.field());
            case Body.Parameter p -> program.element(p.parameter()).name();
            case Body.Default d -> Render.defaultValue(type);
            case Body.Call call -> (call.receiver() == null ? "" : value(call.receiver()) + ".") + program.element(call.method()).name()
                    + "(" + String.join(", ", call.arguments().stream().map(this::value).toList()) + ")";
            case Body.New n -> Render.creation(type(program.element(n.constructor()).parent()),
                    n.arguments().stream().map(this::value).toList());
            case Body.NewList l -> "new " + named("java.util.ArrayList") + "<>()";
        };
    }

    /** A field by its name, or through what it is reached from where a parameter of the same name hides it. */
    private String field(ElementId id) {
        var field = program.element(id);
        if (!parameters.contains(field.name())) return field.name();
        return Render.through("true".equals(field.fact(Fact.STATIC)) ? type(field.parent()) : "this", field.name());
    }

    /** A type as the file can name it: by its simple name, imported once if it must be. */
    private String type(ElementId type) {
        return named(program.element(type).qualified());
    }

    private String named(String qualified) {
        return named.computeIfAbsent(qualified, q -> Imports.nameable(edits, unit, q));
    }

    /** A list type said in no language ({@link Body#listOf}), as the file of {@code site} writes it. */
    static String listType(Context c, ElementId site, EditSet edits, Unit unit, ElementId of) {
        var writer = new WriteBody(c.program(), List.of(), edits, unit, "void", c.program().element(site).parent());
        return writer.named("java.util.List") + "<" + writer.type(of) + ">";
    }
}
