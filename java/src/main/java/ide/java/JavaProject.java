package ide.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import ide.java.syntax.Imports;
import ide.java.syntax.Lists;
import ide.java.syntax.Members;
import ide.java.syntax.Modifiers;
import ide.java.syntax.Render;
import ide.java.text.EditSet;
import ide.java.text.Sources;
import ide.java.text.Unit;
import ide.program.Address;
import ide.program.Change;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Identity;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reference;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.PrimitiveRejected;
import ide.projection.Projection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static ide.java.Resolution.*;

/**
 * Java sources in memory, projected to a {@link Program}. The text is the truth: every primitive is a set of text
 * edits, after which only the touched units are parsed again. Identity is kept in {@code ids} (address → id):
 * parsing never decides identity, primitives carry it along.
 */
public final class JavaProject implements Projection {

    /** Everything needed to come back: source text and identity. */
    public record Snapshot(Map<String, String> sources, Identity.State identity, Set<String> emptyPackages)
            implements Projection.Snapshot {
    }

    private static final JavaRules RULES = new JavaRules();

    private final WorkspaceTypeSolver typeSolver;
    private final GraphReader reader;
    private final Sources sources;
    private final Identity ids = new Identity();
    private final Keys keys = new Keys(ids);
    private Program graph;
    private Map<String, Node> referenceNodes = Map.of();
    private List<Diagnostic> diagnostics;
    private Set<String> emptyPackages = new TreeSet<>();

    private JavaProject() {
        typeSolver = new WorkspaceTypeSolver(this::trees, new ReflectionTypeSolver());
        reader = new GraphReader(ids, keys, typeSolver);
        sources = new Sources(new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver)), this::invalidate);
    }

    private java.util.Collection<CompilationUnit> trees() {
        return sources.trees();
    }

    public static JavaProject of(Map<String, String> sources) {
        var project = new JavaProject();
        project.sources.put(sources);
        return project;
    }

    public String source(String path) {
        return sources.unit(path).text();
    }

    public Set<String> files() {
        return Set.copyOf(sources.paths());
    }

    /** Path → text of every source file, sorted by path. */
    public Map<String, String> sources() {
        return sources.texts();
    }

    @Override
    public ide.projection.LanguageRules rules() {
        return RULES;
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(sources.texts(), ids.save(), Set.copyOf(emptyPackages));
    }

    @Override
    public void restore(Projection.Snapshot saved) {
        var snapshot = (Snapshot) saved;
        sources.restore(snapshot.sources());
        ids.restore(snapshot.identity());
        emptyPackages = new TreeSet<>(snapshot.emptyPackages());
        invalidate();
    }

    @Override
    public List<Diagnostic> diagnostics() {
        if (diagnostics == null) diagnostics = JavacCheck.errors(sources.texts());
        return diagnostics;
    }

    private static final String MARKER = "__ide_complete__";

    /**
     * Inserts a marker identifier at the cursor (and a {@code ;} if needed to parse), parses that copy and resolves
     * the receiver of the marker: the scope's type for {@code expr.prefix}, the enclosing type for a bare name.
     */
    @Override
    public Optional<CompletionContext> completionContext(String path, String text, int offset) {
        program(); // identities of the saved version, which the edited text is matched against
        var start = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        var prefix = text.substring(start, offset);
        var parsed = sources.parseDetached(text.substring(0, offset) + MARKER + text.substring(offset))
                .or(() -> sources.parseDetached(text.substring(0, offset) + MARKER + ";" + text.substring(offset)));
        if (parsed.isEmpty()) return Optional.empty();
        var name = prefix + MARKER;
        var site = parsed.get().findFirst(Node.class, n -> n instanceof NodeWithSimpleName<?> named
                && named.getNameAsString().equals(name) && (n instanceof NameExpr || n instanceof FieldAccessExpr));
        if (site.isEmpty()) return Optional.empty();
        var siteId = reader.siteOf(site.get());
        if (siteId == null) return Optional.empty();
        try {
            if (site.get() instanceof FieldAccessExpr access) {
                var typeName = access.getScope() instanceof NameExpr scopeName ? reader.resolveTypeName(scopeName) : null;
                var receiver = typeName != null ? typeName
                        : access.getScope().calculateResolvedType().asReferenceType().getQualifiedName();
                return Optional.ofNullable(ids.get(new Address.Type(receiver)))
                        .map(r -> new CompletionContext(r, prefix, siteId, typeName != null, List.of()));
            }
            var enclosing = site.get().findAncestor(TypeDeclaration.class).orElseThrow().getFullyQualifiedName().orElseThrow().toString();
            return Optional.ofNullable(ids.get(new Address.Type(enclosing)))
                    .map(r -> new CompletionContext(r, prefix, siteId, false, localsBefore(site.get())));
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    /** Locals declared before {@code node} in its method, then the method's parameters; nearest first. */
    private static List<String> localsBefore(Node node) {
        var method = node.findAncestor(CallableDeclaration.class);
        if (method.isEmpty()) return List.of();
        var position = node.getBegin().orElseThrow();
        var locals = new ArrayList<String>();
        method.get().findAll(VariableDeclarator.class).stream()
                .filter(v -> v.getEnd().orElseThrow().isBefore(position))
                .forEach(v -> locals.addFirst(v.getNameAsString()));
        ((CallableDeclaration<?>) method.get()).getParameters().reversed().forEach(p -> locals.add(p.getNameAsString()));
        return locals;
    }

    @Override
    public Program program() {
        if (graph == null) {
            var read = reader.read(sources.units(), emptyPackages);
            graph = read.program();
            referenceNodes = read.nodes();
        }
        return graph;
    }

    @Override
    public void edit(String path, String text) {
        sources.put(Map.of(path, text));
    }

    // ---- primitives

    /**
     * Applies a primitive as minimal text edits and answers its change: what the primitive means (its intent),
     * plus what only the Java text tells (which uses an extract moved).
     */
    @Override
    public Change apply(Primitive primitive) {
        var before = program();
        return switch (primitive) {
            case Primitive.Create c -> {
                var created = create(c.kind(), c.parent(), c.name(), c.argument());
                c.facts().forEach((fact, value) -> set(created, fact.label(), value)); // born with them
                yield c.intent(before, created);
            }
            case Primitive.Delete d -> {
                delete(d.element());
                yield d.intent(before, null);
            }
            case Primitive.Move m -> {
                move(m.element(), m.parent(), m.before());
                yield m.intent(before, null);
            }
            case Primitive.Rename r -> {
                rename(r.element(), r.name());
                yield r.intent(before, null);
            }
            case Primitive.Set set -> {
                set(set.element(), set.attribute(), set.value());
                yield set.intent(before, null);
            }
            case Primitive.Link link -> {
                link(link.element(), link.mode(), before.element(link.target()).qualified());
                yield link.intent(before, null);
            }
            case Primitive.Retarget r -> {
                retarget(r.reference(), r.target());
                yield r.intent(before, null);
            }
            case Primitive.Extract e -> {
                var created = extract(e.method(), e.fragment(), e.name());
                var change = e.intent(before, created);
                for (var moved : program().referencesFrom(created)) { // the fragment's uses are made from the new method
                    change = change.expect(new Change.Resited(moved.target(), e.method(), created));
                }
                yield change;
            }
            case Primitive.Inline i -> {
                inline(i.reference());
                yield i.intent(before, null);
            }
        };
    }

    /** Makes a declaration name a workspace type: the extends clause, one more interface, or the declared type. */
    private void link(ElementId element, Mode mode, String type) {
        switch (mode) {
            case EXTENDS -> setSuperclass(element, type);
            case IMPLEMENTS -> addImplements(element, type);
            case DECLARED -> setType(element, type);
            default -> throw new IllegalArgumentException("not a link: " + mode);
        }
    }

    /**
     * Creates an element: a top-level class or interface in its own file, an {@code Object} field after the last
     * field, a {@code void} method without parameters at the end of a type, or an {@code Object} parameter at the end
     * of a method (every call passes {@code argument} for it).
     */
    private ElementId create(Kind kind, ElementId parent, String name, String argument) {
        if (kind == Kind.PACKAGE) { // a package exists while it is declared, even before it has files
            for (var part : name.split("\\.", -1)) requireIdentifier(part);
            if (ids.has(new Address.Package(name))) throw new PrimitiveRejected("projection.duplicate", name + " already exists");
            emptyPackages.add(name);
            invalidate();
            return ids.of(new Address.Package(name), Kind.PACKAGE);
        }
        requireIdentifier(name);
        var owner = program().element(parent);
        Address address = switch (kind) {
            case CLASS, INTERFACE -> new Address.Type(owner.qualified() + "." + name);
            case FIELD -> new Address.Field(owner.id(), name);
            case METHOD -> new Address.Callable(owner.id(), name, List.of());
            case PARAM -> new Address.Param(owner.id(), name);
            default -> throw new IllegalArgumentException("unsupported create: " + kind);
        };
        if (ids.has(address)) throw new PrimitiveRejected("projection.duplicate", ids.render(address) + " already exists");
        return switch (kind) {
            case CLASS, INTERFACE -> {
                if (owner.kind() != Kind.PACKAGE) throw new IllegalArgumentException("types go in packages: " + owner);
                var path = owner.qualified().replace('.', '/') + "/" + name + ".java";
                if (sources.has(path)) throw new PrimitiveRejected("projection.duplicate", path + " already exists");
                sources.put(Map.of(path, Render.typeFile(owner.qualified(), kind == Kind.CLASS ? "class" : "interface", name)));
                yield ids.of(address, Kind.CLASS);
            }
            case FIELD -> {
                var type = typeDeclaration(owner.qualified());
                var unit = sources.unitOf(type);
                sources.apply(edits(e -> Members.appendField(e, unit, type, "Object " + name + ";")));
                yield ids.of(address, Kind.FIELD);
            }
            case METHOD -> {
                var type = typeDeclaration(owner.qualified());
                var unit = sources.unitOf(type);
                sources.apply(edits(e -> Members.append(e, unit, type, Render.emptyMethod(name, Members.indentation(unit, type)))));
                yield ids.of(address, Kind.METHOD);
            }
            default -> { // PARAM
                var calls = callsTo(owner.id());
                if (!calls.isEmpty() && argument == null) {
                    throw new PrimitiveRejected("create.argument", "the calls of " + owner.qualified() + " need an argument for " + name);
                }
                var method = (MethodDeclaration) declaration(owner);
                var edits = new EditSet();
                Lists.append(edits, sources.unitOf(method), method.getParameters(), method.getName(), "Object " + name);
                for (var call : calls) Lists.append(edits, sources.unitOf(call), call.getArguments(), call.getName(), argument);
                var names = new ArrayList<>(parameterNames(method));
                names.add(name);
                sources.apply(edits);
                resign(owner, names);
                yield ids.of(address, Kind.PARAM);
            }
        };
    }

    private static void requireIdentifier(String name) {
        if (!javax.lang.model.SourceVersion.isName(name) || name.contains(".")) {
            throw new PrimitiveRejected("projection.invalid-identifier", "'" + name + "' is not a Java identifier");
        }
    }

    /** Moves a member into another type (just before another member, or at the end), keeping its identity. */
    private void move(ElementId member, ElementId newParent, ElementId before) {
        var g = program();
        var element = g.element(member);
        var target = g.element(newParent);
        if (target.kind() == Kind.PACKAGE) {
            moveType(element, target);
            return;
        }
        if (element.kind() == Kind.PARAM) {
            if (!newParent.equals(element.parent())) {
                throw new PrimitiveRejected("projection.unsupported", "a parameter moves within its method");
            }
            moveParam(element, before == null ? null : g.element(before));
            return;
        }
        var moved = ids.address(member).withOwner(newParent);
        if (ids.has(moved)) throw new PrimitiveRejected("projection.duplicate", ids.render(moved) + " already exists");
        var node = declaration(element);
        var from = sources.unitOf(node);
        var type = typeDeclaration(target.qualified());
        var to = sources.unitOf(type);
        var text = Members.text(from, node, Members.indentation(to, type));
        var edits = new EditSet();
        Members.remove(edits, from, node);
        if (before == null) Members.append(edits, to, type, text);
        else Members.insertBefore(edits, to, declaration(g.element(before)), text);
        sources.apply(edits);
        ids.move(member, newParent);
    }

    /**
     * Moves a top-level type to another package: new file and package line; imports for what it used from its old
     * package; in other units the import is rewritten, added (old package users) or dropped (new package users).
     */
    private void moveType(Element type, Element pkg) {
        var oldPackage = packageOf(type.qualified());
        var newQualified = pkg.qualified() + "." + type.name();
        var oldPath = type.fact(Fact.FILE);
        if (oldPath == null) throw new IllegalArgumentException("only top-level types move to packages: " + type);
        var newPath = pkg.qualified().replace('.', '/') + "/" + type.name() + ".java";
        if (sources.has(newPath)) throw new IllegalArgumentException("already exists: " + newPath);

        var moving = sources.unit(oldPath);
        var needed = new TreeSet<String>();
        for (var use : moving.tree().findAll(ClassOrInterfaceType.class)) {
            var resolved = resolveType(use);
            if (resolved != null && use.getScope().isEmpty() && packageOf(resolved).equals(oldPackage)
                    && !resolved.equals(type.qualified()) && !resolved.startsWith(type.qualified() + ".")) {
                needed.add(resolved);
            }
        }
        var users = new HashSet<String>(); // any use: declared types, new, static receivers
        var targets = new ArrayList<>(List.of(type.id()));
        program().children(type.id()).stream().filter(e -> e.kind() == Kind.CONSTRUCTOR).forEach(c -> targets.add(c.id()));
        for (var target : targets) {
            for (var ref : program().referencesTo(target)) users.add(sources.unitOf(referenceNodes.get(ref.id())).path());
        }
        var edits = new EditSet();
        for (var unit : sources.units()) {
            if (unit.path().equals(oldPath)) continue;
            var imported = Imports.of(unit, type.qualified());
            if (imported.isPresent() && packageOf(unit).equals(pkg.qualified())) Imports.remove(edits, unit, imported.get());
            else if (imported.isPresent()) Imports.replace(edits, unit, imported.get(), newQualified);
            else if (users.contains(unit.path()) && packageOf(unit).equals(oldPackage)) Imports.add(edits, unit, newQualified);
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
        ids.rebindType(type.qualified(), newQualified);
    }

    private static ImportDeclaration redundantImport(Unit unit, String pkg) {
        return unit.tree().getImports().stream().filter(i -> !i.isAsterisk() && !i.isStatic()
                && packageOf(i.getNameAsString()).equals(pkg)).findFirst().orElse(null);
    }

    private static String packageOf(String qualifiedType) {
        var dot = qualifiedType.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedType.substring(0, dot);
    }

    private static String packageOf(Unit unit) {
        return unit.tree().getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    }

    /** Deletes an element nothing references: a member's lines (with its comment), a parameter, or a type's file. */
    private void delete(ElementId id) {
        var g = program();
        var element = g.element(id);
        if (element.kind() == Kind.PARAM) {
            deleteParam(element);
            return;
        }
        if (element.kind().isType()) {
            deleteType(element);
            return;
        }
        var uses = g.referencesTo(id).stream().map(r -> g.element(r.site()).qualified()).distinct().toList();
        if (!uses.isEmpty()) throw new PrimitiveRejected("delete.referenced", element.qualified() + " is used by " + uses);
        var node = declaration(element);
        if (node instanceof FieldDeclaration field && field.getVariables().size() > 1) {
            throw new IllegalArgumentException("deleting one of several variables is not supported: " + element);
        }
        var unit = sources.unitOf(node);
        sources.apply(edits(e -> Members.remove(e, unit, node)));
        ids.forget(element.id());
    }

    /** Renames a field, method or parameter and every use that resolves to it, keeping its identity. */
    private void rename(ElementId id, String name) {
        requireIdentifier(name);
        var element = program().element(id);
        if (element.kind().isType()) {
            renameType(element, name);
            return;
        }
        var renamed = ids.address(id).withName(name);
        if (ids.has(renamed)) throw new PrimitiveRejected("projection.duplicate", ids.render(renamed) + " already exists");
        var node = declaration(element);
        var unit = sources.unitOf(node);
        var edits = new EditSet();
        switch (node) {
            case Parameter param -> edits.replace(unit, param.getName(), name);
            case FieldDeclaration field -> field.getVariables().stream().filter(v -> v.getNameAsString().equals(element.name()))
                    .forEach(v -> edits.replace(unit, v.getName(), name));
            case MethodDeclaration method -> edits.replace(unit, method.getName(), name);
            default -> throw new IllegalArgumentException("unsupported rename: " + element);
        }
        for (var ref : program().referencesTo(id)) renameUse(edits, referenceNodes.get(ref.id()), name);
        sources.apply(edits);
        ids.rename(id, name);
    }

    /** A use now names {@code name}: the name of a field access, a call, a bare name or a method reference. */
    private void renameUse(EditSet edits, Node use, String name) {
        var unit = sources.unitOf(use);
        switch (use) {
            case MethodReferenceExpr reference -> {
                var end = reference.getEnd().orElseThrow();
                var begin = new com.github.javaparser.Position(end.line, end.column - reference.getIdentifier().length() + 1);
                edits.replace(unit, begin, end, name);
            }
            case NodeWithSimpleName<?> named -> edits.replace(unit, named.getName(), name);
            default -> throw new IllegalStateException("cannot rename the use " + use);
        }
    }

    /**
     * Renames a type: its declaration and constructors, every use (declared types, {@code new}, static receivers),
     * single-type imports of it, and its file when it is a top-level type named like the file.
     */
    private void renameType(Element type, String name) {
        var dot = type.qualified().lastIndexOf('.');
        var renamed = type.qualified().substring(0, dot + 1) + name;
        if (ids.has(new Address.Type(renamed))) throw new PrimitiveRejected("projection.duplicate", renamed + " already exists");
        var g = program();
        var names = new ArrayList<Node>();
        var targets = new ArrayList<ElementId>(List.of(type.id()));
        g.children(type.id()).stream().filter(e -> e.kind() == Kind.CONSTRUCTOR).forEach(c -> targets.add(c.id()));
        for (var target : targets) {
            for (var ref : g.referencesTo(target)) {
                switch (referenceNodes.get(ref.id())) {
                    case ClassOrInterfaceType t -> names.add(t.getName());
                    case ObjectCreationExpr creation -> names.add(creation.getType().getName());
                    case NameExpr receiver -> names.add(receiver);
                    case Node other -> throw new IllegalStateException("unexpected type use " + other);
                }
            }
        }
        var declaration = typeDeclaration(type.qualified());
        declaration.getConstructors().forEach(c -> names.add(c.getName()));
        names.add(declaration.getName());
        var edits = new EditSet();
        for (var node : names) edits.replace(sources.unitOf(node), node, name);
        for (var unit : sources.units()) Imports.of(unit, type.qualified()).ifPresent(i -> edits.replace(unit, i.getName(), renamed));
        sources.apply(edits);
        var oldPath = type.fact(Fact.FILE);
        if (oldPath != null && oldPath.endsWith("/" + type.name() + ".java")) {
            sources.rename(oldPath, oldPath.substring(0, oldPath.length() - (type.name() + ".java").length()) + name + ".java");
        }
        ids.rebindType(type.qualified(), renamed);
    }

    /** Changes an attribute of an element, as text: modifiers, types, a body, an import, an initializer. */
    private void set(ElementId id, String attribute, String value) {
        switch (attribute) {
            case "visibility" -> modifiers(id, (edits, unit, node) -> Modifiers.visibility(edits, unit, node, value));
            case "static" -> modifiers(id, (edits, unit, node) -> Modifiers.flag(edits, unit, node, Modifier.Keyword.STATIC, Boolean.parseBoolean(value)));
            case "final" -> modifiers(id, (edits, unit, node) -> Modifiers.flag(edits, unit, node, Modifier.Keyword.FINAL, Boolean.parseBoolean(value)));
            case "abstract" -> setAbstract(id, Boolean.parseBoolean(value));
            case "superclass" -> setSuperclass(id, value);
            case "type" -> setType(id, value);
            case "body" -> setBody(id, value);
            case "import" -> addImport(id, value);
            case "initializer" -> setInitializer(id, value);
            default -> throw new IllegalArgumentException("unsupported attribute: " + attribute);
        }
    }

    private interface ModifierEdit {
        void make(EditSet edits, Unit unit, NodeWithModifiers<?> declaration);
    }

    private void modifiers(ElementId id, ModifierEdit edit) {
        var node = declaration(program().element(id));
        var unit = sources.unitOf(node);
        sources.apply(edits(e -> edit.make(e, unit, (NodeWithModifiers<?>) node)));
    }

    /** Calls anywhere in the workspace that resolve to the method, from the current graph. */
    private List<MethodCallExpr> callsTo(ElementId method) {
        var calls = new ArrayList<MethodCallExpr>();
        for (var ref : program().referencesTo(method)) {
            if (referenceNodes.get(ref.id()) instanceof MethodCallExpr call) calls.add(call);
        }
        return calls;
    }

    /** Moves a parameter just before another (or last), in the declaration and in every call. */
    private void moveParam(Element param, Element before) {
        var method = program().element(param.parent());
        var declaration = (MethodDeclaration) declaration(method);
        var names = parameterNames(declaration);
        var from = names.indexOf(param.name());
        var anchor = before == null ? names.size() : names.indexOf(before.name());
        var index = anchor > from ? anchor - 1 : anchor; // where it goes once it is out of the list
        if (index == from) return;
        var edits = new EditSet();
        reorder(edits, declaration.getParameters(), from, index);
        for (var call : callsTo(method.id())) reorder(edits, call.getArguments(), from, index);
        var reordered = new ArrayList<>(names);
        reordered.add(index, reordered.remove(from));
        sources.apply(edits);
        resign(method, reordered);
    }

    /** Deletes a top-level type nothing uses: its file goes, and so do the imports that name it. */
    private void deleteType(Element type) {
        var g = program();
        var uses = g.referencesTo(type.id()).stream().filter(r -> !inside(g, r.site(), type.id()))
                .map(r -> g.element(r.site()).qualified()).distinct().toList();
        if (!uses.isEmpty()) throw new PrimitiveRejected("delete.referenced", type.qualified() + " is used by " + uses);
        var path = type.fact(Fact.FILE);
        if (path == null) throw new IllegalArgumentException("only top-level types can be deleted so far: " + type);
        var edits = new EditSet();
        for (var unit : sources.units()) Imports.of(unit, type.qualified()).ifPresent(i -> Imports.remove(edits, unit, i));
        sources.change(edits.texts(), List.of(path));
        ids.forget(type.id());
    }

    private static boolean inside(Program g, ElementId id, ElementId container) {
        for (var e = g.element(id); ; e = g.element(e.parent())) {
            if (e.id().equals(container)) return true;
            if (e.parent() == null) return false;
        }
    }

    /** Removes a parameter the body does not use, and the matching argument from every call. */
    private void deleteParam(Element param) {
        var method = program().element(param.parent());
        var declaration = (MethodDeclaration) declaration(method);
        var used = declaration.findAll(NameExpr.class, n -> n.getNameAsString().equals(param.name())).stream()
                .anyMatch(n -> {
                    try {
                        return n.resolve().isParameter();
                    } catch (RuntimeException unresolved) {
                        return false;
                    }
                });
        if (used) throw new PrimitiveRejected("delete.referenced", param.qualified() + " is used in the body of " + method.qualified());
        var names = new ArrayList<>(parameterNames(declaration));
        var index = names.indexOf(param.name());
        var edits = new EditSet();
        remove(edits, declaration.getParameters(), index);
        for (var call : callsTo(method.id())) remove(edits, call.getArguments(), index);
        names.remove(index);
        sources.apply(edits);
        ids.forget(param.id());
        resign(method, names);
    }

    private void reorder(EditSet edits, com.github.javaparser.ast.NodeList<? extends Node> items, int from, int to) {
        var unit = sources.unitOf(items.getFirst().orElseThrow());
        var texts = new ArrayList<>(Lists.texts(unit, items));
        texts.add(to, texts.remove(from));
        Lists.rewrite(edits, unit, items, texts);
    }

    private void remove(EditSet edits, com.github.javaparser.ast.NodeList<? extends Node> items, int index) {
        var unit = sources.unitOf(items.getFirst().orElseThrow());
        var texts = new ArrayList<>(Lists.texts(unit, items));
        texts.remove(index);
        Lists.rewrite(edits, unit, items, texts);
    }

    private static List<String> parameterNames(CallableDeclaration<?> callable) {
        return callable.getParameters().stream().map(p -> p.getNameAsString()).toList();
    }

    /** After a parameter list change, the method's identity follows its re-parsed declaration (found by its parameter names). */
    private void resign(Element method, List<String> parameterNames) {
        var callable = (Address.Callable) ids.address(method.id());
        var declaration = typeDeclaration(ids.render(ids.address(callable.owner()))).getMethodsByName(method.name()).stream()
                .filter(m -> parameterNames(m).equals(parameterNames))
                .findFirst().orElseThrow();
        ids.resign(method.id(), keys.of(declaration));
    }

    /** Adds an import to the unit of a top-level type. */
    private void addImport(ElementId id, String qualified) {
        var type = typeDeclaration(program().element(id).qualified());
        var unit = sources.unitOf(type);
        sources.apply(edits(e -> Imports.add(e, unit, qualified)));
    }

    /** The declared type, as written; a workspace type given by qualified name is imported and written simply. */
    private void setType(ElementId id, String type) {
        var element = program().element(id);
        var node = declaration(element);
        var unit = sources.unitOf(node);
        // a parameter's type is part of its method's address: what the method is, is read before the text changes
        var owner = element.kind() == Kind.PARAM ? program().element(element.parent()) : null;
        var names = owner == null ? null : parameterNames((CallableDeclaration<?>) declaration(owner));
        var edits = new EditSet();
        var written = type.contains(".") && ids.has(new Address.Type(type)) ? importing(edits, unit, type) : type;
        switch (node) {
            case FieldDeclaration field -> field.getVariables().forEach(v -> edits.replace(unit, v.getType(), written));
            case MethodDeclaration method -> edits.replace(unit, method.getType(), written);
            case Parameter param -> edits.replace(unit, param.getType(), written);
            default -> throw new IllegalArgumentException("unsupported type change: " + element);
        }
        sources.apply(edits);
        if (owner != null) resign(owner, names);
    }

    /** Replaces a method body with a one-statement-per-line template; {@code #{id}} renders as the element's name. */
    private void setBody(ElementId id, String template) {
        var method = (MethodDeclaration) declaration(program().element(id));
        var unit = sources.unitOf(method);
        var indent = unit.indentation(method.getBegin().orElseThrow().line);
        sources.apply(edits(e -> e.replace(unit, method.getBody().orElseThrow(), Render.block(render(template), indent))));
    }

    private String render(String template) {
        var matcher = java.util.regex.Pattern.compile("#\\{([^}]+)}").matcher(template);
        return matcher.replaceAll(m -> program().element(new ElementId(m.group(1))).name());
    }

    /** Writes the extends clause: a qualified name of the same package as a simple name, any other text as it is. */
    private void setSuperclass(ElementId id, String superclass) {
        var type = (ClassOrInterfaceDeclaration) typeDeclaration(program().element(id).qualified());
        var unit = sources.unitOf(type);
        var name = simpleIfSamePackage(unit, superclass);
        var edits = new EditSet();
        if (type.getExtendedTypes().isEmpty()) edits.insertAt(unit, afterNameAndTypeParameters(unit, type), " extends " + name);
        else edits.replace(unit, type.getExtendedTypes(0), name); // the whole type: its arguments belonged to the old one
        sources.apply(edits);
    }

    /** One more interface a class implements (or an interface extends). */
    private void addImplements(ElementId id, String qualified) {
        var type = (ClassOrInterfaceDeclaration) typeDeclaration(program().element(id).qualified());
        var unit = sources.unitOf(type);
        var name = simpleIfSamePackage(unit, qualified);
        var list = type.isInterface() ? type.getExtendedTypes() : type.getImplementedTypes();
        var edits = new EditSet();
        if (list.isNonEmpty()) edits.insertAfter(unit, list.getLast().orElseThrow(), ", " + name);
        else if (type.getExtendedTypes().isNonEmpty()) edits.insertAfter(unit, type.getExtendedTypes().getLast().orElseThrow(), " implements " + name);
        else edits.insertAt(unit, afterNameAndTypeParameters(unit, type), (type.isInterface() ? " extends " : " implements ") + name);
        sources.apply(edits);
    }

    private static String simpleIfSamePackage(Unit unit, String qualified) {
        var pkg = packageOf(unit);
        return !pkg.isEmpty() && qualified.startsWith(pkg + ".") && qualified.indexOf('.', pkg.length() + 1) < 0
                ? qualified.substring(pkg.length() + 1) : qualified;
    }

    private static com.github.javaparser.Position afterNameAndTypeParameters(Unit unit, ClassOrInterfaceDeclaration type) {
        if (type.getTypeParameters().isEmpty()) return Unit.after(type.getName().getEnd().orElseThrow());
        return Unit.after(unit.next('>', type.getTypeParameters().getLast().orElseThrow().getEnd().orElseThrow()));
    }

    /**
     * A method without body (in an interface just the signature, in a class an abstract method), or an abstract
     * class.
     */
    private void setAbstract(ElementId id, boolean on) {
        var element = program().element(id);
        if (!on) throw new IllegalArgumentException("unsupported: abstract=false on " + element);
        if (element.kind() == Kind.CLASS) {
            modifiers(id, (edits, unit, node) -> Modifiers.flag(edits, unit, node, Modifier.Keyword.ABSTRACT, true));
            return;
        }
        if (element.kind() != Kind.METHOD) throw new IllegalArgumentException("unsupported: abstract on " + element);
        var method = (MethodDeclaration) declaration(element);
        var unit = sources.unitOf(method);
        var edits = new EditSet();
        if (program().element(element.parent()).kind() != Kind.INTERFACE) {
            Modifiers.flag(edits, unit, method, Modifier.Keyword.ABSTRACT, true);
        }
        var body = method.getBody().orElseThrow();
        var signatureEnd = unit.previousToken(body.getBegin().orElseThrow());
        edits.replace(unit, Unit.after(signatureEnd), body.getEnd().orElseThrow(), ";");
        sources.apply(edits);
    }

    /** The initial value of a field, as written. */
    private void setInitializer(ElementId id, String value) {
        var element = program().element(id);
        var field = (FieldDeclaration) declaration(element);
        var unit = sources.unitOf(field);
        var variable = field.getVariables().stream().filter(v -> v.getNameAsString().equals(element.name())).findFirst().orElseThrow();
        var edits = new EditSet();
        if (variable.getInitializer().isPresent()) edits.replace(unit, variable.getInitializer().get(), value);
        else edits.insertAfter(unit, variable.getName(), " = " + value);
        sources.apply(edits);
    }

    private ElementId extract(ElementId from, String fragment, String name) {
        requireIdentifier(name);
        var source = program().element(from);
        var owner = program().element(source.parent());
        var method = (MethodDeclaration) declaration(source);
        var dash = fragment.indexOf('-');
        var first = Integer.parseInt(fragment.substring(0, dash).strip());
        var last = Integer.parseInt(fragment.substring(dash + 1).strip());
        var selected = Bodies.statementsWithin(method, first, last);
        if (selected.isEmpty()) throw new PrimitiveRejected("extract.fragment", "lines " + fragment + " are not whole statements of one block");
        var jump = Bodies.jumpOut(selected);
        if (jump != null) throw new PrimitiveRejected("extract.jump", "'" + jump + "' leaves the fragment");
        var flow = Bodies.flow(method, selected);
        if (flow.outputs().size() > 1) {
            throw new PrimitiveRejected("extract.outputs", "more than one value is used afterwards: " + flow.outputs());
        }
        for (var input : flow.inputs().values()) {
            if (input.isVarType()) throw new PrimitiveRejected("extract.var-type", "cannot name the type of a 'var' input");
        }
        var output = flow.outputs().isEmpty() ? null : flow.outputs().keySet().iterator().next();
        var outputType = output == null ? "void" : flow.outputs().get(output).asString();
        var params = flow.inputs().entrySet().stream().map(e -> e.getValue().asString() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        var unit = sources.unitOf(method);
        var type = typeDeclaration(owner.qualified());
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
        sources.apply(edits);
        var created = typeDeclaration(owner.qualified()).getMethodsByName(name).getFirst();
        return ids.of(new Address.Callable(owner.id(), name, keys.of(created)), Kind.METHOD);
    }

    /**
     * Inlines a call to a method whose body is a single {@code return expr;}: each parameter becomes its argument
     * (parenthesized where precedence needs it), and so does the whole expression at the call site. Refused:
     * recursion, a body using implicit {@code this} members called on another receiver, and a non-trivial argument
     * whose parameter is not used exactly once (it would be evaluated another number of times).
     */
    private void inline(String refId) {
        var ref = program().reference(refId);
        if (!(referenceNodes.get(refId) instanceof MethodCallExpr call)) {
            throw new PrimitiveRejected("inline.not-a-call", refId + " is not a call");
        }
        var method = program().element(ref.target());
        var declaration = (MethodDeclaration) declaration(method);
        var statements = declaration.getBody().map(b -> b.getStatements()).orElseThrow();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt() || statements.get(0).asReturnStmt().getExpression().isEmpty()) {
            throw new PrimitiveRejected("inline.body-shape", method.qualified() + " is not a single 'return expr;'");
        }
        var original = statements.get(0).asReturnStmt().getExpression().get(); // attached, so names resolve
        if (original.findAll(MethodCallExpr.class).stream().anyMatch(c -> method.id().equals(reader.resolveMethod(c)))) {
            throw new PrimitiveRejected("inline.recursive", method.qualified() + " calls itself");
        }
        var thisMembers = original.findAll(NameExpr.class).stream().anyMatch(n -> resolveField(n) != null)
                || original.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getScope().isEmpty());
        if (thisMembers && call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
            throw new PrimitiveRejected("inline.receiver", method.qualified() + " uses members of this; the call has another receiver");
        }
        var body = original.clone(); // a detached copy, rewritten and printed: the text it becomes
        var params = declaration.getParameters();
        var args = call.getArguments();
        for (var i = 0; i < params.size(); i++) {
            var name = params.get(i).getNameAsString();
            var uses = body.findAll(NameExpr.class, n -> n.getNameAsString().equals(name));
            if (uses.size() != 1 && !Bodies.trivial(args.get(i))) {
                throw new PrimitiveRejected("inline.evaluation-count", args.get(i) + " would be evaluated " + uses.size() + " times");
            }
        }
        // substitute on the copy of the body; replacing a name that is the whole body replaces the root
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
        var unit = sources.unitOf(call);
        sources.apply(edits(e -> e.replace(unit, call, text)));
    }

    /**
     * Points a use at another element. A use of the same kind just names the other one; a field read becomes
     * {@code getter()}, a write {@code x = v} used as a statement {@code setter(v)}, a {@code new} a factory call.
     * Updates (compound assignment, ++/--) and assignments whose value is used are rejected: they would change how
     * many times things are evaluated.
     */
    private void retarget(String refId, ElementId targetId) {
        var ref = program().reference(refId);
        if (ref.mode().isTypeUse()) {
            retargetType(ref, program().element(targetId));
            return;
        }
        var node = (Expression) referenceNodes.get(refId);
        if (node.isObjectCreationExpr() && ref.mode() != Mode.NEW) throw new IllegalStateException(ref.toString());
        var target = program().element(targetId);
        var unit = sources.unitOf(node);
        var edits = new EditSet();
        if (target.kind() == program().element(ref.target()).kind()) { // same kind: the use just names the other one
            renameUse(edits, node, target.name());
            sources.apply(edits);
            return;
        }
        if (target.kind() != Kind.METHOD) throw new IllegalArgumentException("can only retarget to methods: " + target);
        var receiver = node.isFieldAccessExpr() ? unit.textOf(node.asFieldAccessExpr().getScope()) + "." : "";
        switch (ref.mode()) {
            case READ -> edits.replace(unit, node, receiver + target.name() + "()");
            case NEW -> {
                var creation = (ObjectCreationExpr) node;
                if (creation.getAnonymousClassBody().isPresent()) {
                    throw new PrimitiveRejected("retarget.anonymous-class", "cannot turn " + creation + " into a call");
                }
                var arguments = creation.getArguments().isEmpty() ? "" : unit.textBetween(
                        creation.getArguments().getFirst().orElseThrow().getBegin().orElseThrow(),
                        creation.getArguments().getLast().orElseThrow().getEnd().orElseThrow());
                edits.replace(unit, creation, creation.getType().getNameWithScope() + "." + target.name() + "(" + arguments + ")");
            }
            case WRITE -> {
                var assign = (AssignExpr) node.getParentNode().orElseThrow();
                if (!(assign.getParentNode().orElseThrow() instanceof ExpressionStmt)) {
                    throw new PrimitiveRejected("retarget.value-used", "the value of " + assign + " is used");
                }
                edits.replace(unit, assign, receiver + target.name() + "(" + unit.textOf(assign.getValue()) + ")");
            }
            default -> throw new PrimitiveRejected("retarget." + ref.mode().label(),
                    "cannot express " + node.getParentNode().orElseThrow() + " with " + target.qualified());
        }
        sources.apply(edits);
    }

    /** Makes {@code qualified} nameable by its simple name in {@code unit}, importing it if needed; returns that name. */
    private static String importing(EditSet edits, Unit unit, String qualified) {
        if (!Imports.names(unit, qualified)) Imports.add(edits, unit, qualified);
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** A type use now names {@code type}: its simple name, imported when another package declares it. */
    private void retargetType(Reference ref, Element type) {
        var node = referenceNodes.get(ref.id());
        var unit = sources.unitOf(node);
        var edits = new EditSet();
        edits.replace(unit, node instanceof ClassOrInterfaceType t ? t.getName() : node, type.name());
        if (!Imports.names(unit, type.qualified())) Imports.add(edits, unit, type.qualified());
        sources.apply(edits);
    }

    // ---- declarations and edits

    private Node declaration(Element element) {
        if (element.kind() == Kind.PARAM) {
            return ((CallableDeclaration<?>) declaration(program().element(element.parent())))
                    .getParameterByName(element.name()).orElseThrow();
        }
        if (element.kind().isType()) return typeDeclaration(element.qualified());
        var type = typeDeclaration(program().element(element.parent()).qualified());
        return switch (element.kind()) {
            case FIELD -> type.getFieldByName(element.name()).orElseThrow();
            case CONSTRUCTOR -> type.getConstructors().stream()
                    .filter(c -> keys.of(c).equals(keysOf(element)))
                    .findFirst().orElseThrow();
            case METHOD -> type.getMethods().stream()
                    .filter(m -> m.getNameAsString().equals(element.name()) && keys.of(m).equals(keysOf(element)))
                    .findFirst().orElseThrow();
            default -> throw new IllegalArgumentException("not a member: " + element);
        };
    }

    private static List<String> keysOf(Element callable) {
        return ((Address.Callable) callable.address()).keys();
    }

    private TypeDeclaration<?> typeDeclaration(String qualified) {
        return sources.trees().stream()
                .flatMap(cu -> types(cu).stream())
                .filter(t -> t.getFullyQualifiedName().map(Object::toString).orElse("").equals(qualified))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no type " + qualified));
    }

    private static EditSet edits(java.util.function.Consumer<EditSet> making) {
        var edits = new EditSet();
        making.accept(edits);
        return edits;
    }

    /** After any change: what was read or resolved before describes another program. */
    private void invalidate() {
        JavaParserFacade.clearInstances(); // resolution caches point into replaced trees
        sources.trees().forEach(JavaProject::forgetResolvedTypes);
        typeSolver.reset();
        graph = null;
        referenceNodes = Map.of();
        diagnostics = null;
    }

    /**
     * The facade also caches resolved types on the nodes themselves; in units that were not parsed again they would
     * still describe the program before the change (e.g. a field whose declared type changed elsewhere).
     */
    private static void forgetResolvedTypes(CompilationUnit cu) {
        cu.walk(node -> {
            for (var key : List.copyOf(node.getDataKeys())) {
                if (key.getClass().getName().startsWith(JavaParserFacade.class.getName() + "$")) node.removeData(key);
            }
        });
    }
}
