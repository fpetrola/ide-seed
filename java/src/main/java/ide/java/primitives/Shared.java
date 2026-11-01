package ide.java.primitives;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import ide.program.Kind;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import ide.java.semantics.Reader;
import ide.java.semantics.Resolution;
import ide.java.text.EditSet;
import ide.java.text.Unit;
import ide.program.Address;
import ide.program.Element;
import ide.program.ElementId;
import ide.projection.PrimitiveRejected;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** What several primitives do alike. */
final class Shared {

    private Shared() {
    }

    static void requireIdentifier(String name) {
        if (!javax.lang.model.SourceVersion.isName(name) || name.contains(".")) {
            throw new PrimitiveRejected("projection.invalid-identifier", "'" + name + "' is not a Java identifier");
        }
    }

    static EditSet edits(Consumer<EditSet> making) {
        var edits = new EditSet();
        making.accept(edits);
        return edits;
    }

    /**
     * A call as its arguments are written: in the first parentheses after {@code from}, the end of a method's name or of
     * the type a {@code new} makes, or the start of a {@code super(...)} or {@code this(...)}.
     */
    record Call(Node node, NodeList<Expression> arguments, Position from) {
    }

    /** The calls that resolve to a method, or the {@code new}s that resolve to a constructor, as last read. */
    static List<Call> callsTo(Context c, ElementId callable) {
        var calls = new ArrayList<Call>();
        for (var ref : c.program().referencesTo(callable)) {
            switch (c.use(ref.id())) {
                case MethodCallExpr call -> calls.add(new Call(call, call.getArguments(), call.getName().getEnd().orElseThrow()));
                case ObjectCreationExpr creation -> calls.add(new Call(creation, creation.getArguments(), creation.getType().getEnd().orElseThrow()));
                case ExplicitConstructorInvocationStmt invocation -> calls.add(new Call(invocation, invocation.getArguments(), invocation.getBegin().orElseThrow()));
                default -> {
                    // a method reference passes no arguments
                }
            }
        }
        return calls;
    }

    static List<String> parameterNames(CallableDeclaration<?> callable) {
        return callable.getParameters().stream().map(p -> p.getNameAsString()).toList();
    }

    /**
     * After its parameters changed, a method's identity follows it to its new address: found in the unit just edited
     * ({@code path}) by its name and parameter names, since the reading is stale until then.
     */
    static void resign(Context c, Element method, String path, List<String> parameterNames) {
        var callable = (Address.Callable) c.ids().address(method.id());
        var owner = c.ids().render(c.ids().address(callable.owner()));
        var type = typeIn(c.sources().unit(path), owner);
        var callables = method.kind() == Kind.CONSTRUCTOR ? List.<CallableDeclaration<?>>copyOf(type.getConstructors())
                : List.<CallableDeclaration<?>>copyOf(type.getMethodsByName(method.name()));
        var declaration = callables.stream().filter(m -> parameterNames(m).equals(parameterNames)).findFirst().orElseThrow();
        c.ids().resign(method.id(), c.keys().of(declaration));
    }

    /** A type in a unit just edited. */
    static TypeDeclaration<?> typeIn(Unit unit, String qualified) {
        return Resolution.types(unit.tree()).stream().filter(t -> Reader.qualifiedOf(t).equals(qualified)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no type " + qualified + " in " + unit.path()));
    }

    /** A use now names {@code name}: the name of a field access, a call, a bare name or a method reference. */
    static void renameUse(Context c, EditSet edits, Node use, String name) {
        var unit = c.sources().unitOf(use);
        switch (use) {
            case MethodReferenceExpr reference -> {
                var end = reference.getEnd().orElseThrow();
                edits.replace(unit, new Position(end.line, end.column - reference.getIdentifier().length() + 1), end, name);
            }
            case NodeWithSimpleName<?> named -> edits.replace(unit, named.getName(), name);
            default -> throw new IllegalStateException("cannot rename the use " + use);
        }
    }

    static String packageOf(String qualifiedType) {
        var dot = qualifiedType.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedType.substring(0, dot);
    }
}
