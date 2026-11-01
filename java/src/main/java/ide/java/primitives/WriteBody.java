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
 * A body in no language ({@link Body}) written in Java, in place of a method's: a field or a parameter by its name, a
 * field through {@code this} (or through its type, if static) where a parameter of the same name hides it, and a new
 * object by its type's simple name, imported if the file cannot name it yet. The default is the one of the method's
 * type, as a field would start with it; a void method answers nothing, and a call it would answer is a statement. A
 * body that keeps what the method does is written after its last statement.
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

    static void apply(Context c, ElementId method, String text) {
        var program = c.program();
        var body = Body.parse(text, program, method);
        var declaration = c.declaration(program.element(method));
        com.github.javaparser.ast.body.CallableDeclaration<?> callable = declaration.as(com.github.javaparser.ast.body.CallableDeclaration.class);
        var unit = declaration.unit();
        var edits = new EditSet();
        var type = callable instanceof MethodDeclaration m ? m.getType().asString() : "void";
        var writer = new WriteBody(program, Shared.parameterNames(callable), edits, unit, type, program.element(method).parent());
        var statements = body.statements().stream().map(writer::statement).filter(s -> !s.isEmpty()).collect(Collectors.joining("\n"));
        var indent = unit.indentation(callable.getBegin().orElseThrow().line);
        var block = callable instanceof MethodDeclaration m ? m.getBody().orElseThrow()
                : ((com.github.javaparser.ast.body.ConstructorDeclaration) callable).getBody();
        if (body.keeps() && block.getStatements().isNonEmpty()) { // after what it does, as it is written
            edits.insertAfter(unit, block.getStatements().getLast().orElseThrow(),
                    statements.lines().map(line -> "\n" + indent + "    " + line).collect(Collectors.joining()));
        } else {
            edits.replace(unit, block, Render.block(statements, indent));
        }
        c.sources().apply(edits);
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
            case Body.Kept k -> "";
            case Body.Construct made -> (program.element(made.constructor()).parent().equals(owner) ? "this" : "super")
                    + "(" + String.join(", ", made.arguments().stream().map(this::value).toList()) + ");";
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
        return named.computeIfAbsent(program.element(type).qualified(), qualified -> Imports.nameable(edits, unit, qualified));
    }
}
