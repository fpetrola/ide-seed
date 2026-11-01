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
 * object by its type's simple name, imported if the file cannot name it yet.
 */
final class WriteBody {

    private final Program program;
    private final List<String> parameters;
    private final EditSet edits;
    private final Unit unit;
    private final Map<String, String> named = new HashMap<>();

    private WriteBody(Program program, List<String> parameters, EditSet edits, Unit unit) {
        this.program = program;
        this.parameters = parameters;
        this.edits = edits;
        this.unit = unit;
    }

    static void apply(Context c, ElementId method, String text) {
        var program = c.program();
        var body = Body.parse(text, program, method);
        var declaration = c.declaration(program.element(method));
        var callable = declaration.as(MethodDeclaration.class);
        var unit = declaration.unit();
        var edits = new EditSet();
        var writer = new WriteBody(program, Shared.parameterNames(callable), edits, unit);
        var statements = body.statements().stream().map(writer::statement).collect(Collectors.joining("\n"));
        var indent = unit.indentation(callable.getBegin().orElseThrow().line);
        edits.replace(unit, callable.getBody().orElseThrow(), Render.block(statements, indent));
        c.sources().apply(edits);
    }

    private String statement(Body.Statement statement) {
        return switch (statement) {
            case Body.Return r -> Render.returning(value(r.value()));
            case Body.Assign a -> Render.assignment(field(a.field()), value(a.value()));
        };
    }

    private String value(Body.Expression expression) {
        return switch (expression) {
            case Body.Field f -> field(f.field());
            case Body.Parameter p -> program.element(p.parameter()).name();
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
