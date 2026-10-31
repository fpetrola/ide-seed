package ide.java.primitives;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import ide.java.semantics.Bodies;
import ide.java.syntax.Members;
import ide.java.text.EditSet;
import ide.program.Address;
import ide.program.Change;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import java.util.stream.Collectors;

/**
 * Moves the statements of a method on lines {@code first-last} into a new private method that the method calls:
 * inputs become parameters, at most one value used afterwards becomes the result. Refuses fragments that are not
 * whole statements of one block, that jump out, or that have more than one output. The change also says which uses
 * the new method makes now.
 */
final class ExtractFragment {

    private ExtractFragment() {
    }

    static Change apply(Primitive.Extract extract, Context c, Program before) {
        var name = extract.name();
        Shared.requireIdentifier(name);
        var source = before.element(extract.method());
        var owner = before.element(source.parent());
        var declaration = c.declaration(source);
        var method = declaration.as(MethodDeclaration.class);
        var dash = extract.fragment().indexOf('-');
        var first = Integer.parseInt(extract.fragment().substring(0, dash).strip());
        var last = Integer.parseInt(extract.fragment().substring(dash + 1).strip());
        var selected = Bodies.statementsWithin(method, first, last);
        if (selected.isEmpty()) {
            throw new PrimitiveRejected("extract.fragment", "lines " + extract.fragment() + " are not whole statements of one block");
        }
        var jump = Bodies.jumpOut(selected);
        if (jump != null) throw new PrimitiveRejected("extract.jump", "'" + jump + "' leaves the fragment");
        var flow = Bodies.flow(method, selected);
        if (flow.outputs().size() > 1) throw new PrimitiveRejected("extract.outputs", "more than one value is used afterwards: " + flow.outputs());
        for (var input : flow.inputs().values()) {
            if (input.isVarType()) throw new PrimitiveRejected("extract.var-type", "cannot name the type of a 'var' input");
        }
        var output = flow.outputs().isEmpty() ? null : flow.outputs().keySet().iterator().next();
        var outputType = output == null ? "void" : flow.outputs().get(output).asString();
        var params = flow.inputs().entrySet().stream().map(e -> e.getValue().asString() + " " + e.getKey()).collect(Collectors.joining(", "));

        var unit = declaration.unit();
        var type = c.declaration(owner).as(TypeDeclaration.class);
        var indent = Members.indentation(unit, type);
        var begin = selected.getFirst().getBegin().orElseThrow().line;
        var end = selected.getLast().getEnd().orElseThrow().line;
        var body = Bodies.reindent(unit.lines(begin, end), indent + "    ");
        if (output != null) body += indent + "    return " + output + ";\n";
        var call = name + "(" + String.join(", ", flow.inputs().keySet()) + ")";
        var callLine = output == null ? call + ";"
                : flow.declaredInside(output) ? outputType + " " + output + " = " + call + ";"
                : output + " = " + call + ";";
        var modifiers = method.isStatic() ? "private static " : "private ";
        var edits = new EditSet().replaceLines(unit, begin, end, unit.indentation(begin) + callLine + "\n");
        Members.append(edits, unit, type, modifiers + outputType + " " + name + "(" + params + ") {\n" + body + indent + "}");
        c.sources().apply(edits);

        var created = Shared.typeIn(c.sources().unit(unit.path()), owner.qualified()).getMethodsByName(name).getFirst();
        var id = c.ids().of(new Address.Callable(owner.id(), name, c.keys().of(created)), Kind.METHOD);
        var change = extract.intent(before, id);
        for (var moved : c.program().referencesFrom(id)) { // the fragment's uses are made from the new method
            change = change.expect(new Change.Resited(moved.target(), source.id(), id));
        }
        return change;
    }
}
