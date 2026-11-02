package ide.java.primitives;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import ide.java.syntax.Imports;
import ide.java.syntax.Lists;
import ide.java.semantics.Bodies;
import ide.java.syntax.Members;
import ide.java.syntax.Statements;
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
 * (a new file, and the imports that follow), a statement before another of a body or at its end, in its method or
 * another one (one that declares locals stays: they would have to go with it).
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
        else if (element.kind() == Kind.STATEMENT) moveStatement(c, before, element, target, anchor);
        else {
            var change = move.intent(before, null);
            for (var gone : moveMember(c, before, element, target, anchor)) change = change.expect(gone);
            return change;
        }
        return move.intent(before, null);
    }

    /**
     * A member into another type. Going to another file, the types it names by their simple names are named there too
     * (imported as its file imports them, or from its package); a static one is used from elsewhere through its new
     * type, written as each file can name it.
     */
    private static List<Change.Dropped> moveMember(Context c, Program before, Element member, Element target, Element anchor) {
        var moved = c.ids().address(member.id()).withOwner(target.id());
        if (c.ids().has(moved)) throw new PrimitiveRejected("projection.duplicate", c.ids().render(moved) + " already exists");
        var declaration = c.declaration(member);
        var destination = c.declaration(target);
        var type = destination.as(TypeDeclaration.class);
        var text = Members.text(declaration.unit(), declaration.node(), Members.indentation(destination.unit(), type));
        var edits = new EditSet();
        if (!destination.unit().path().equals(declaration.unit().path())) {
            for (var qualified : named(c, before, declaration.unit(), declaration.node())) Imports.nameable(edits, destination.unit(), qualified);
        }
        var gone = "true".equals(member.fact(Fact.STATIC)) ? through(c, before, member, target, edits) : List.<Change.Dropped>of();
        Members.remove(edits, declaration.unit(), declaration.node());
        if (anchor == null) Members.append(edits, destination.unit(), type, text);
        else Members.insertBefore(edits, destination.unit(), c.declaration(anchor).node(), text);
        c.sources().apply(edits);
        c.ids().move(member.id(), target.id());
        return gone;
    }

    /** The types a declaration names by a simple name its file can name them by: imported, or of its package. */
    private static TreeSet<String> named(Context c, Program before, Unit unit, Node node) {
        var simple = new TreeSet<String>();
        node.findAll(ClassOrInterfaceType.class, t -> t.getScope().isEmpty()).forEach(t -> simple.add(t.getNameAsString()));
        for (var scope : node.findAll(com.github.javaparser.ast.expr.NameExpr.class)) { // Type.member: a name before a dot
            if (scope.getParentNode().filter(p -> p instanceof com.github.javaparser.ast.expr.MethodCallExpr call && call.getScope().filter(scope::equals).isPresent()
                    || p instanceof com.github.javaparser.ast.expr.FieldAccessExpr access && access.getScope() == scope).isPresent()) simple.add(scope.getNameAsString());
        }
        var named = new TreeSet<String>();
        for (var name : simple) {
            unit.tree().getImports().stream().filter(i -> !i.isAsterisk() && !i.isStatic() && i.getName().getIdentifier().equals(name))
                    .findFirst().ifPresentOrElse(i -> named.add(i.getNameAsString()), () -> {
                        var pkg = Imports.packageOf(unit);
                        var local = pkg.isEmpty() ? name : pkg + "." + name;
                        if (!pkg.isEmpty() && before.find(local).filter(e -> e.kind().isType()).isPresent()) named.add(local);
                    });
        }
        return named;
    }

    /**
     * The uses of a static member made outside the type it goes to name it through that type; what the type they named
     * it through used is used no more.
     */
    private static List<Change.Dropped> through(Context c, Program before, Element member, Element target, EditSet edits) {
        var gone = new ArrayList<Change.Dropped>();
        for (var use : before.referencesTo(member.id())) {
            if (before.within(use.site(), member.id()) || inside(before, use.site(), target)) continue;
            var node = c.use(use.id());
            var unit = c.sources().unitOf(node);
            var name = Imports.nameable(edits, unit, target.qualified());
            switch (node) {
                case com.github.javaparser.ast.expr.NameExpr read -> edits.replace(unit, read, name + "." + member.name());
                case com.github.javaparser.ast.expr.MethodCallExpr call when call.getScope().isEmpty() -> edits.replace(unit, call.getName(), name + "." + member.name());
                case com.github.javaparser.ast.expr.MethodCallExpr call -> {
                    edits.replace(unit, call.getScope().orElseThrow(), name);
                    gone.addAll(within(c, before, use.site(), call.getScope().orElseThrow()));
                }
                case com.github.javaparser.ast.expr.FieldAccessExpr access -> {
                    edits.replace(unit, access.getScope(), name);
                    gone.addAll(within(c, before, use.site(), access.getScope()));
                }
                default -> {
                    // a method reference, or what names it otherwise, stays as written: the verifier sees what it binds
                }
            }
        }
        return gone;
    }

    /** The uses {@code site} makes inside {@code node}: they go with it. */
    private static List<Change.Dropped> within(Context c, Program before, ide.program.ElementId site, Node node) {
        var range = node.getRange().orElseThrow();
        return before.referencesFrom(site).stream().filter(r -> c.use(r.id()).getRange().filter(range::contains).isPresent())
                .map(r -> new Change.Dropped(r.site(), r.target())).toList();
    }

    /** Whether an element is in {@code type}, or in a type inside it. */
    private static boolean inside(Program program, ide.program.ElementId element, Element type) {
        for (var at = program.enclosingType(element); ; at = program.enclosingType(at.parent())) {
            if (at.id().equals(type.id())) return true;
            if (at.parent() == null || program.element(at.parent()).kind() == Kind.PACKAGE) return false;
        }
    }

    private static void moveStatement(Context c, Program before, Element statement, Element method, Element anchor) {
        if (anchor != null && !method.id().equals(anchor.parent())) {
            throw new PrimitiveRejected("move.before", anchor.qualified() + " is not a statement of " + method.qualified());
        }
        if (anchor != null && anchor.id().equals(statement.id())) return;
        var declaration = c.declaration(statement);
        var unit = declaration.unit();
        var node = declaration.node();
        if (!Statements.alone(unit, node)) throw new PrimitiveRejected("move.unsupported", statement.qualified() + " shares its lines");
        var declared = node.findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class);
        if (!declared.isEmpty() && !method.id().equals(statement.parent())) {
            throw new PrimitiveRejected("move.unsupported", statement.qualified() + " declares locals, which would have to go with it");
        }
        if (!declared.isEmpty()) { // within its method its locals stay its method's: each keeps its number while none of its name is passed
            var names = declared.stream().flatMap(d -> d.getVariables().stream()).map(v -> v.getNameAsString()).toList();
            var same = before.children(method.id()).stream().filter(e -> e.kind() == Kind.LOCAL && names.contains(e.name())).count();
            if (same > names.size()) throw new PrimitiveRejected("move.unsupported", statement.qualified() + " declares a local named as another of its method");
        }
        var destination = c.declaration(method);
        CallableDeclaration<?> callable = destination.as(CallableDeclaration.class);
        var statements = Bodies.statements(callable);
        var at = destination.unit();
        var indent = statements.isEmpty() ? at.indentation(callable.getBegin().orElseThrow().line) + "    "
                : at.indentation(statements.getFirst().getBegin().orElseThrow().line);
        var text = Members.text(unit, node, indent);
        var edits = new EditSet();
        Members.remove(edits, unit, node);
        if (anchor == null) Statements.append(edits, at, Bodies.body(callable), indent, text);
        else Statements.insertBefore(edits, at, c.declaration(anchor).node(), text);
        var source = statement.parent();
        var left = before.children(source).stream().filter(e -> e.kind() == Kind.STATEMENT && !e.id().equals(statement.id()))
                .map(Element::id).toList();
        var placed = new ArrayList<>(source.equals(method.id()) ? left : before.children(method.id()).stream()
                .filter(e -> e.kind() == Kind.STATEMENT).map(Element::id).toList());
        placed.add(anchor == null ? placed.size() : placed.indexOf(anchor.id()), statement.id());
        c.sources().apply(edits);
        c.ids().placeStatements(method.id(), placed);
        if (!source.equals(method.id())) c.ids().placeStatements(source, left);
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
        var newPath = Shared.fileFor(sources, pkg.qualified(), type.name());
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
