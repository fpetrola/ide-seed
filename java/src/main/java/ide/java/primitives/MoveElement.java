package ide.java.primitives;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ide.java.syntax.Imports;
import ide.java.syntax.Lists;
import ide.java.syntax.Members;
import ide.java.text.EditSet;
import ide.java.text.Unit;
import ide.program.Change;
import ide.program.Element;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.PrimitiveRejected;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;

import static ide.java.semantics.Resolution.resolveType;

/**
 * Moves an element, keeping its identity: a member into another type (with its comment, just before another member
 * or at the end), a parameter within its method (the calls' arguments follow), a top-level type to another package
 * (a new file, and the imports that follow).
 */
final class MoveElement {

    private MoveElement() {
    }

    static Change apply(Primitive.Move move, Context c, Program before) {
        var element = before.element(move.element());
        var target = before.element(move.parent());
        var anchor = move.before() == null ? null : before.element(move.before());
        if (target.kind() == Kind.PACKAGE) moveType(c, before, element, target);
        else if (element.kind() == Kind.PARAM) moveParam(c, element, target, anchor);
        else moveMember(c, element, target, anchor);
        return move.intent(before, null);
    }

    private static void moveMember(Context c, Element member, Element target, Element anchor) {
        var moved = c.ids().address(member.id()).withOwner(target.id());
        if (c.ids().has(moved)) throw new PrimitiveRejected("projection.duplicate", c.ids().render(moved) + " already exists");
        var declaration = c.declaration(member);
        var destination = c.declaration(target);
        var type = destination.as(TypeDeclaration.class);
        var text = Members.text(declaration.unit(), declaration.node(), Members.indentation(destination.unit(), type));
        var edits = new EditSet();
        Members.remove(edits, declaration.unit(), declaration.node());
        if (anchor == null) Members.append(edits, destination.unit(), type, text);
        else Members.insertBefore(edits, destination.unit(), c.declaration(anchor).node(), text);
        c.sources().apply(edits);
        c.ids().move(member.id(), target.id());
    }

    private static void moveParam(Context c, Element param, Element method, Element anchor) {
        if (!method.id().equals(param.parent())) {
            throw new PrimitiveRejected("projection.unsupported", "a parameter moves within its method");
        }
        var declaration = c.declaration(method);
        CallableDeclaration<?> node = declaration.as(CallableDeclaration.class);
        var names = Shared.parameterNames(node);
        var from = names.indexOf(param.name());
        var at = anchor == null ? names.size() : names.indexOf(anchor.name());
        var index = at > from ? at - 1 : at; // where it goes once it is out of the list
        if (index == from) return;
        var edits = new EditSet();
        reorder(edits, declaration.unit(), node.getParameters(), from, index);
        for (var call : Shared.callsTo(c, method.id())) reorder(edits, c.sources().unitOf(call.node()), call.arguments(), from, index);
        var reordered = new ArrayList<>(names);
        reordered.add(index, reordered.remove(from));
        c.sources().apply(edits);
        Shared.resign(c, method, declaration.unit().path(), reordered);
    }

    private static void reorder(EditSet edits, Unit unit, NodeList<? extends Node> items, int from, int to) {
        var texts = new ArrayList<>(Lists.texts(unit, items));
        texts.add(to, texts.remove(from));
        Lists.rewrite(edits, unit, items, texts);
    }

    /**
     * A new file and package line; imports for what the type used from its old package; in other units the import is
     * rewritten, added (users in the old package) or dropped (users in the new one).
     */
    private static void moveType(Context c, Program before, Element type, Element pkg) {
        var sources = c.sources();
        var oldPackage = Shared.packageOf(type.qualified());
        var newQualified = pkg.qualified() + "." + type.name();
        var oldPath = type.fact(Fact.FILE);
        if (oldPath == null) throw new IllegalArgumentException("only top-level types move to packages: " + type);
        var newPath = pkg.qualified().replace('.', '/') + "/" + type.name() + ".java";
        if (sources.has(newPath)) throw new IllegalArgumentException("already exists: " + newPath);

        var moving = sources.unit(oldPath);
        var needed = new TreeSet<String>();
        for (var use : moving.tree().findAll(ClassOrInterfaceType.class)) {
            var resolved = resolveType(use);
            if (resolved != null && use.getScope().isEmpty() && Shared.packageOf(resolved).equals(oldPackage)
                    && !resolved.equals(type.qualified()) && !resolved.startsWith(type.qualified() + ".")) {
                needed.add(resolved);
            }
        }
        var users = new HashSet<String>(); // any use: declared types, new, static receivers
        var targets = new ArrayList<>(List.of(type.id()));
        before.children(type.id()).stream().filter(e -> e.kind() == Kind.CONSTRUCTOR).forEach(k -> targets.add(k.id()));
        for (var target : targets) {
            for (var ref : before.referencesTo(target)) users.add(sources.unitOf(c.use(ref.id())).path());
        }
        var edits = new EditSet();
        for (var unit : sources.units()) {
            if (unit.path().equals(oldPath)) continue;
            var imported = Imports.of(unit, type.qualified());
            var unitPackage = Imports.packageOf(unit);
            if (imported.isPresent() && unitPackage.equals(pkg.qualified())) Imports.remove(edits, unit, imported.get());
            else if (imported.isPresent()) Imports.replace(edits, unit, imported.get(), newQualified);
            else if (users.contains(unit.path()) && unitPackage.equals(oldPackage)) Imports.add(edits, unit, newQualified);
        }

        // the moved file, step by step: imports for its old package, none for its new one, the new package line
        var draft = moving;
        for (var qualified : needed) {
            var adding = new EditSet();
            Imports.add(adding, draft, qualified);
            draft = sources.draft(oldPath, adding.texts().get(oldPath));
        }
        for (var redundant = redundantImport(draft, pkg.qualified()); redundant != null; redundant = redundantImport(draft, pkg.qualified())) {
            var removing = new EditSet();
            Imports.remove(removing, draft, redundant);
            draft = sources.draft(oldPath, removing.texts().get(oldPath));
        }
        var packageLine = new EditSet().replace(draft, draft.tree().getPackageDeclaration().orElseThrow(), "package " + pkg.qualified() + ";");

        var texts = new LinkedHashMap<>(edits.texts());
        texts.put(newPath, packageLine.texts().get(oldPath));
        sources.change(texts, List.of(oldPath));
        c.ids().rebindType(type.qualified(), newQualified);
    }

    private static ImportDeclaration redundantImport(Unit unit, String pkg) {
        return unit.tree().getImports().stream().filter(i -> !i.isAsterisk() && !i.isStatic()
                && Shared.packageOf(i.getNameAsString()).equals(pkg)).findFirst().orElse(null);
    }
}
