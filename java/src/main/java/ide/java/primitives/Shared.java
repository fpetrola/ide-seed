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

    /** A body written anew, or gone: its statements and its locals are no one now. */
    static void forgetBody(Context c, ide.program.Program before, ElementId callable) {
        for (var element : before.children(callable)) if (element.kind().inBody()) c.ids().forget(element.id());
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
     * the type a {@code new} makes, or the start of a {@code super(...)} or {@code this(...)}; made from {@code site}.
     */
    record Call(Node node, NodeList<Expression> arguments, Position from, ElementId site) {
    }

    /** The calls that resolve to a method, or the {@code new}s that resolve to a constructor, as last read. */
    static List<Call> callsTo(Context c, ElementId callable) {
        var calls = new ArrayList<Call>();
        for (var ref : c.program().referencesTo(callable)) {
            switch (c.use(ref.id())) {
                case MethodCallExpr call -> calls.add(new Call(call, call.getArguments(), call.getName().getEnd().orElseThrow(), ref.site()));
                case ObjectCreationExpr creation -> calls.add(new Call(creation, creation.getArguments(), creation.getType().getEnd().orElseThrow(), ref.site()));
                case ExplicitConstructorInvocationStmt invocation -> calls.add(new Call(invocation, invocation.getArguments(), invocation.getBegin().orElseThrow(), ref.site()));
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

    /**
     * Where the file of a top-level type {@code name} of package {@code pkg} goes: in a source root with files of that
     * package, else in any; of those, the one with most files (a file's root is its folder without its package's).
     */
    static String fileFor(ide.java.text.Sources sources, String pkg, String name) {
        var dirs = pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/";
        var roots = new java.util.TreeMap<String, Integer>();
        var withPackage = new java.util.HashSet<String>();
        for (var unit : sources.units()) {
            var path = unit.path();
            var folder = path.substring(0, path.lastIndexOf('/') + 1);
            var declared = unit.tree().getPackageDeclaration().map(d -> d.getNameAsString().replace('.', '/') + "/").orElse("");
            if (!folder.endsWith(declared)) continue; // a file not where its package says: no root to learn from it
            var root = folder.substring(0, folder.length() - declared.length());
            roots.merge(root, 1, Integer::sum);
            if (declared.equals(dirs)) withPackage.add(root);
        }
        var root = roots.entrySet().stream().filter(e -> withPackage.isEmpty() || withPackage.contains(e.getKey()))
                .max(java.util.Map.Entry.comparingByValue()).map(java.util.Map.Entry::getKey).orElse("");
        return root + dirs + name + ".java";
    }

    static String packageOf(String qualifiedType) {
        var dot = qualifiedType.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedType.substring(0, dot);
    }

    /** What a use of a member is made through, when it is a type: a field's {@code Type.f}, a call's {@code Type.m()}. */
    static Node typeScope(Context c, ide.program.Program before, ide.program.Reference use) {
        var scope = switch (c.use(use.id())) {
            case com.github.javaparser.ast.expr.FieldAccessExpr access -> access.getScope();
            case com.github.javaparser.ast.expr.MethodCallExpr call -> call.getScope().orElse(null);
            default -> null;
        };
        if (scope == null || !(scope.isNameExpr() || scope.isFieldAccessExpr())) return null;
        var range = scope.getRange().orElseThrow();
        var named = before.referencesFrom(use.site()).stream().filter(r -> !r.mode().isLink() && c.use(r.id()) != null
                && c.use(r.id()).getRange().filter(range::contains).isPresent()).toList();
        return !named.isEmpty() && named.stream().allMatch(r -> before.element(r.target()).kind().isType()) ? scope : null;
    }

    /** The text a use made through a type is, made with none: {@code instead} before a field a name there hides. */
    static String unscoped(Context c, ide.program.Reference use, String name, String instead) {
        var node = c.use(use.id());
        var unit = c.sources().unitOf(node);
        if (node instanceof com.github.javaparser.ast.expr.MethodCallExpr call) return unit.textBetween(call.getName().getBegin().orElseThrow(), call.getEnd().orElseThrow());
        var hidden = node.findAncestor(com.github.javaparser.ast.body.CallableDeclaration.class).filter(m -> m.getParameters().stream()
                .anyMatch(p -> ((com.github.javaparser.ast.body.Parameter) p).getNameAsString().equals(name))
                || ide.java.semantics.Bodies.locals((com.github.javaparser.ast.body.CallableDeclaration<?>) m).stream().anyMatch(v -> v.getNameAsString().equals(name))).isPresent();
        return hidden ? instead + "." + name : name;
    }
}
