package ide.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;

import ide.projection.PrimitiveRejected;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.program.Element;

import static ide.java.Resolution.*;
import ide.program.Address;
import ide.program.Change;
import ide.program.ElementId;
import ide.program.Identity;
import ide.program.Fact;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Kind;
import ide.projection.Projection;
import ide.program.Program;
import ide.program.Reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Java sources in memory, edited with lexical preservation, projected to a {@link Program}.
 * Identity is kept in {@code ids} (address → id): parsing never decides identity, primitives transfer it.
 */
public final class JavaProject implements Projection {

    /** Everything needed to roll back: source text and identity. */
    public record Snapshot(Map<String, String> sources, Identity.State identity, java.util.Set<String> emptyPackages)
            implements Projection.Snapshot {
    }

    private static final Set<Modifier.Keyword> ACCESS =
            Set.of(Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE);

    private final JavaParser parser;
    private final WorkspaceTypeSolver typeSolver;
    private final GraphReader reader;
    private final Map<String, CompilationUnit> units = new TreeMap<>();
    private final Identity ids = new Identity();
    private final Keys keys = new Keys(ids);
    private Program graph;
    private Map<String, Node> referenceNodes = Map.of();
    private List<Diagnostic> diagnostics;
    private final Map<String, String> parsedText = new HashMap<>();
    private java.util.Set<String> emptyPackages = new java.util.TreeSet<>();

    private JavaProject() {
        var solver = new WorkspaceTypeSolver(units::values, new ReflectionTypeSolver());
        typeSolver = solver;
        reader = new GraphReader(ids, keys, solver);
        parser = new JavaParser(new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(solver)));
    }

    public static JavaProject of(Map<String, String> sources) {
        var project = new JavaProject();
        sources.forEach(project::load);
        return project;
    }

    public String source(String path) {
        return LexicalPreservingPrinter.print(units.get(path));
    }

    public java.util.Set<String> files() {
        return java.util.Set.copyOf(units.keySet());
    }

    @Override
    public Snapshot snapshot() {
        var sources = new LinkedHashMap<String, String>();
        units.keySet().forEach(path -> sources.put(path, source(path)));
        return new Snapshot(sources, ids.save(), java.util.Set.copyOf(emptyPackages));
    }

    @Override
    public void restore(Projection.Snapshot saved) {
        var snapshot = (Snapshot) saved;
        units.clear();
        parsedText.clear();
        snapshot.sources().forEach(this::load);
        ids.restore(snapshot.identity());
        emptyPackages = new java.util.TreeSet<>(snapshot.emptyPackages());
        invalidate();
    }

    @Override
    public List<Diagnostic> diagnostics() {
        if (diagnostics == null) {
            var sources = new LinkedHashMap<String, String>();
            units.keySet().forEach(path -> sources.put(path, source(path)));
            diagnostics = JavacCheck.errors(sources);
        }
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
        var marked = text.substring(0, offset) + MARKER + text.substring(offset);
        var parsed = parser.parse(marked);
        if (!parsed.isSuccessful()) parsed = parser.parse(text.substring(0, offset) + MARKER + ";" + text.substring(offset));
        if (!parsed.isSuccessful()) return Optional.empty();
        var cu = parsed.getResult().orElseThrow();
        var name = prefix + MARKER;
        var site = cu.findFirst(Node.class, n -> n instanceof NodeWithSimpleName<?> named
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
            var read = reader.read(units, emptyPackages);
            graph = read.program();
            referenceNodes = read.nodes();
        }
        return graph;
    }

    // ---- primitives

    /**
     * Applies a primitive as minimal text edits and answers its change: what the primitive means (its intent),
     * plus what only the Java text tells (which uses an extract moved; which link a set of a superclass or a
     * declared type changed).
     */
    @Override
    public Change apply(Primitive primitive) {
        var before = program();
        return switch (primitive) {
            case Primitive.Create c -> c.intent(before, create(c.kind(), c.parent(), c.name()));
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
                var oldLink = link(before, set.element(), set.attribute());
                set(set.element(), set.attribute(), set.value());
                var change = set.intent(before, null);
                var newLink = link(program(), set.element(), set.attribute());
                if (oldLink.isPresent() && newLink.isPresent() && !oldLink.get().equals(newLink.get())) {
                    change = change.expect(new Change.Retargeted(set.element(), oldLink.get(), newLink.get()));
                }
                yield change;
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

    /** The workspace type an attribute links to: the superclass, or the declared type. */
    private static Optional<ElementId> link(Program program, ElementId element, String attribute) {
        var linked = switch (attribute) {
            case "superclass" -> program.superclass(element);
            case "type" -> program.declaredType(element);
            default -> Optional.<Element>empty();
        };
        return linked.map(Element::id);
    }

    /**
     * Creates an empty element: a top-level class in its own unit, a {@code void} method without parameters at the
     * end of a type, or an {@code Object} parameter at the end of a method.
     */
    private ElementId create(Kind kind, ElementId parent, String name) {
        if (kind == Kind.PACKAGE) { // a package exists while it is declared, even before it has files
            for (var part : name.split("\\.", -1)) requireIdentifier(part);
            if (ids.has(new Address.Package(name))) throw new PrimitiveRejected("projection.duplicate", name + " already exists");
            emptyPackages.add(name);
            invalidate();
            return ids.of(new Address.Package(name), Kind.PACKAGE);
        }
        requireIdentifier(name);
        var owner = program().element(parent);
        return switch (kind) {
            case CLASS -> {
                if (owner.kind() != Kind.PACKAGE) throw new IllegalArgumentException("classes go in packages: " + owner);
                var path = owner.qualified().replace('.', '/') + "/" + name + ".java";
                if (units.containsKey(path)) throw new IllegalArgumentException("already exists: " + path);
                load(path, "package " + owner.qualified() + ";\n\npublic class " + name + " {\n}\n");
                yield ids.of(new Address.Type(owner.qualified() + "." + name), Kind.CLASS);
            }
            case INTERFACE -> {
                if (owner.kind() != Kind.PACKAGE) throw new IllegalArgumentException("interfaces go in packages: " + owner);
                var path = owner.qualified().replace('.', '/') + "/" + name + ".java";
                if (units.containsKey(path)) throw new IllegalArgumentException("already exists: " + path);
                load(path, "package " + owner.qualified() + ";\n\npublic interface " + name + " {\n}\n");
                yield ids.of(new Address.Type(owner.qualified() + "." + name), Kind.CLASS);
            }
            case FIELD -> {
                insertField(typeDeclaration(owner.qualified()), "Object " + name + ";");
                yield ids.of(new Address.Field(owner.id(), name), Kind.FIELD);
            }
            case METHOD -> {
                insertMember(typeDeclaration(owner.qualified()), "void " + name + "() {\n" + " ".repeat(4) + "}");
                yield ids.of(new Address.Callable(owner.id(), name, List.of()), Kind.METHOD);
            }
            case PARAM -> {
                // calls get a placeholder argument until the parameter's default is set
                var calls = callsTo(owner.id());
                var marker = new NameExpr(defaultMarker(owner.name(), name));
                editMethod(owner, m -> {
                    m.addParameter("Object", name);
                    calls.forEach(call -> call.addArgument(marker.clone()));
                });
                yield ids.of(new Address.Param(owner.id(), name), Kind.PARAM);
            }
            default -> throw new IllegalArgumentException("unsupported create: " + kind);
        };
    }

    private static void requireIdentifier(String name) {
        if (!javax.lang.model.SourceVersion.isName(name) || name.contains(".")) {
            throw new PrimitiveRejected("projection.invalid-identifier", "'" + name + "' is not a Java identifier");
        }
    }

    /** Edits a method's declaration; its identity follows the parameter list to the re-parsed declaration. */
    private void editMethod(Element method, java.util.function.Consumer<MethodDeclaration> edit) {
        var node = (MethodDeclaration) declaration(method);
        edit.accept(node);
        var names = node.getParameters().stream().map(p -> p.getNameAsString()).toList();
        changed();
        resign(method, names);
    }

    /** Moves a member into another type, keeping its identity. */
    private void move(ElementId member, ElementId newParent, ElementId before) {
        var g = program();
        var element = g.element(member);
        var target = g.element(newParent);
        if (target.kind() == Kind.PACKAGE) {
            moveType(element, target);
            return;
        }
        var anchor = before == null ? null : g.element(before);
        var moved = ids.address(member).withOwner(newParent);
        if (ids.has(moved)) throw new PrimitiveRejected("projection.duplicate", ids.render(moved) + " already exists");
        var node = declaration(element);
        var text = LexicalPreservingPrinter.print(node).strip();
        node.remove();
        changed();
        if (anchor == null) insertMember(typeDeclaration(target.qualified()), text);
        else insertBefore(declaration(anchor), text);
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
        if (units.containsKey(newPath)) throw new IllegalArgumentException("already exists: " + newPath);

        var needed = new java.util.TreeSet<String>();
        for (var use : units.get(oldPath).findAll(ClassOrInterfaceType.class)) {
            var resolved = resolveType(use);
            if (resolved != null && use.getScope().isEmpty() && packageOf(resolved).equals(oldPackage)
                    && !resolved.equals(type.qualified()) && !resolved.startsWith(type.qualified() + ".")) {
                needed.add(resolved);
            }
        }
        var usingUnits = new java.util.HashSet<CompilationUnit>(); // any use: declared types, new, static receivers
        for (var ref : program().referencesTo(type.id())) {
            referenceNodes.get(ref.id()).findCompilationUnit().ifPresent(usingUnits::add);
        }
        for (var constructor : program().children(type.id()).stream().filter(e -> e.kind() == Kind.CONSTRUCTOR).toList()) {
            for (var ref : program().referencesTo(constructor.id())) {
                referenceNodes.get(ref.id()).findCompilationUnit().ifPresent(usingUnits::add);
            }
        }
        var edits = new LinkedHashMap<String, String>();
        for (var entry : units.entrySet()) {
            var path = entry.getKey();
            var cu = entry.getValue();
            if (path.equals(oldPath)) continue;
            var unitPackage = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            var imported = cu.getImports().stream().filter(i -> !i.isAsterisk() && !i.isStatic()
                    && i.getNameAsString().equals(type.qualified())).findFirst();
            var uses = usingUnits.contains(cu);
            var code = source(path);
            if (imported.isPresent() && unitPackage.equals(pkg.qualified())) edits.put(path, Imports.remove(code, cu, imported.get()));
            else if (imported.isPresent()) edits.put(path, Imports.replace(code, imported.get(), newQualified));
            else if (uses && unitPackage.equals(oldPackage)) edits.put(path, Imports.add(code, cu, newQualified));
        }

        var code = source(oldPath);
        for (var qualified : needed) code = Imports.add(code, parse(code), qualified);
        for (var redundant = redundantImport(parse(code), pkg.qualified()); redundant != null; redundant = redundantImport(parse(code), pkg.qualified())) {
            code = Imports.remove(code, parse(code), redundant); // the destination's types need no import there
        }
        var packageLine = parse(code).getPackageDeclaration().orElseThrow();
        code = Text.replace(code, packageLine, "package " + pkg.qualified() + ";");

        units.remove(oldPath);
        parsedText.remove(oldPath);
        units.put(newPath, parse(newPath, code));
        edits.forEach((path, edited) -> units.put(path, parse(path, edited)));
        invalidate();
        ids.rebindType(type.qualified(), newQualified);
    }

    private static ImportDeclaration redundantImport(CompilationUnit cu, String pkg) {
        return cu.getImports().stream().filter(i -> !i.isAsterisk() && !i.isStatic()
                && packageOf(i.getNameAsString()).equals(pkg)).findFirst().orElse(null);
    }

    private static String packageOf(String qualifiedType) {
        var dot = qualifiedType.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedType.substring(0, dot);
    }

    /**
     * Deletes a member (with its comment) that nothing references: its lines go, and a blank line left doubled
     * or against a brace goes with them.
     */
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
        var path = pathOf(node.findCompilationUnit().orElseThrow());
        var code = source(path);
        var first = node.getComment().flatMap(Node::getBegin).orElse(node.getBegin().orElseThrow()).line;
        var last = node.getEnd().orElseThrow().line;
        load(path, Text.collapseBlankAt(Text.removeLines(code, first, last), first));
        ids.forget(element.id());
    }

    /** Renames a field or method together with every use that resolves to it, keeping its identity. */
    private void rename(ElementId id, String name) {
        requireIdentifier(name);
        var element = program().element(id);
        if (element.kind().isType()) {
            renameType(element, name);
            return;
        }
        var renamed = ids.address(id).withName(name);
        if (ids.has(renamed)) throw new PrimitiveRejected("projection.duplicate", ids.render(renamed) + " already exists");
        var uses = usesOf(element);
        var node = declaration(element);
        switch (node) {
            case com.github.javaparser.ast.body.Parameter param -> param.setName(name);
            case FieldDeclaration field -> field.getVariables().stream()
                    .filter(v -> v.getNameAsString().equals(element.name())).forEach(v -> v.setName(name));
            case MethodDeclaration method -> method.setName(name);
            default -> throw new IllegalArgumentException("unsupported rename: " + element);
        }
        uses.forEach(use -> use.setName(name));
        methodReferencesTo(element).forEach(reference -> reference.setIdentifier(name));
        ids.rename(id, name);
        changed();
    }

    /**
     * Renames a type: its declaration and constructors, every use (declared types, {@code new}, static receivers),
     * single-type imports of it, and its file when it is a top-level type named like the file. Done as text edits on
     * the name tokens: the printer does not reflect renamed types inside field declarations.
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
        var edits = new LinkedHashMap<String, List<Map.Entry<Node, String>>>();
        var seen = new java.util.HashSet<String>();
        for (var node : names) {
            var path = pathOf(node.findCompilationUnit().orElseThrow());
            if (seen.add(path + node.getRange().orElseThrow())) {
                edits.computeIfAbsent(path, k -> new ArrayList<>()).add(Map.entry(node, name));
            }
        }
        units.forEach((path, cu) -> cu.getImports().stream()
                .filter(i -> !i.isAsterisk() && !i.isStatic() && i.getNameAsString().equals(type.qualified()))
                .forEach(i -> edits.computeIfAbsent(path, k -> new ArrayList<>()).add(Map.entry(i.getName(), renamed))));
        var oldPath = type.fact(Fact.FILE);
        applyEdits(edits);
        if (oldPath != null && oldPath.endsWith("/" + type.name() + ".java")) {
            var newPath = oldPath.substring(0, oldPath.length() - (type.name() + ".java").length()) + name + ".java";
            units.put(newPath, units.remove(oldPath));
            parsedText.put(newPath, parsedText.remove(oldPath));
            invalidate();
        }
        ids.rebindType(type.qualified(), renamed);
    }

    private List<MethodReferenceExpr> methodReferencesTo(Element element) {
        var g = program();
        var references = new ArrayList<MethodReferenceExpr>();
        for (var ref : g.referencesTo(element.id())) {
            if (referenceNodes.get(ref.id()) instanceof MethodReferenceExpr reference) references.add(reference);
        }
        return references;
    }

    /** Expressions anywhere in the workspace that use the element, from the current graph. */
    private List<NodeWithSimpleName<?>> usesOf(Element element) {
        var g = program();
        var uses = new ArrayList<NodeWithSimpleName<?>>();
        for (var ref : g.referencesTo(element.id())) {
            if (referenceNodes.get(ref.id()) instanceof NodeWithSimpleName<?> named) uses.add(named);
        }
        return uses;
    }

    @Override
    public void edit(String path, String text) {
        var result = parser.parse(text);
        if (!result.isSuccessful()) {
            throw new PrimitiveRejected("projection.unparsable", path + ": " + result.getProblems().getFirst().getVerboseMessage());
        }
        load(path, text);
    }

    /** Changes an attribute of an element: {@code visibility} of a member, {@code superclass} of a class. */
    private void set(ElementId id, String attribute, String value) {
        switch (attribute) {
            case "visibility" -> setVisibility(id, value);
            case "superclass" -> setSuperclass(id, value);
            case "type" -> setType(id, value);
            case "body" -> setBody(id, value);
            case "import" -> addImport(id, value);
            case "default" -> setDefault(id, value);
            case "static" -> setFlag(id, Modifier.Keyword.STATIC, Boolean.parseBoolean(value));
            case "abstract" -> setAbstract(id, Boolean.parseBoolean(value));
            case "final" -> setFlag(id, Modifier.Keyword.FINAL, Boolean.parseBoolean(value));
            case "initializer" -> ((FieldDeclaration) declaration(program().element(id))).getVariables().stream()
                    .filter(v -> v.getNameAsString().equals(program().element(id).name()))
                    .forEach(v -> v.setInitializer(parser.parseExpression(value).getResult().orElseThrow()));
            case "implements" -> addImplements(id, value);
            case "order" -> setOrder(id, Integer.parseInt(value));
            default -> throw new IllegalArgumentException("unsupported attribute: " + attribute);
        }
        changed();
    }

    private static String defaultMarker(String method, String param) {
        return "__ide_default_" + method + "_" + param + "__";
    }

    /** Calls anywhere in the workspace that resolve to the method, from the current graph. */
    private List<MethodCallExpr> callsTo(ElementId method) {
        var g = program();
        var calls = new ArrayList<MethodCallExpr>();
        for (var ref : g.referencesTo(method)) {
            if (referenceNodes.get(ref.id()) instanceof MethodCallExpr call) calls.add(call);
        }
        return calls;
    }

    /** The argument the calls pass for a parameter created in this transaction. */
    private void setDefault(ElementId id, String value) {
        var param = program().element(id);
        var marker = defaultMarker(program().element(param.parent()).name(), param.name());
        var edits = new LinkedHashMap<String, List<Map.Entry<Node, String>>>();
        units.forEach((path, cu) -> cu.findAll(NameExpr.class, n -> n.getNameAsString().equals(marker))
                .forEach(n -> edits.computeIfAbsent(path, k -> new ArrayList<>()).add(Map.entry(n, value))));
        applyEdits(edits);
    }

    /** Moves a parameter to position {@code index}, in the declaration and in every call. */
    private void setOrder(ElementId id, int index) {
        var param = program().element(id);
        var method = program().element(param.parent());
        var declaration = (MethodDeclaration) declaration(method);
        var from = declaration.getParameters().indexOf(declaration.getParameterByName(param.name()).orElseThrow());
        var edits = new LinkedHashMap<String, List<Map.Entry<Node, String>>>();
        reorder(edits, declaration.getParameters(), from, index);
        for (var call : callsTo(method.id())) reorder(edits, call.getArguments(), from, index);
        var names = new ArrayList<>(declaration.getParameters().stream().map(p -> p.getNameAsString()).toList());
        names.add(index, names.remove(from));
        applyEdits(edits);
        resign(method, names);
    }

    /** Deletes a top-level type nothing uses: its file goes, and so do the imports that name it. */
    private void deleteType(Element type) {
        var g = program();
        var uses = g.referencesTo(type.id()).stream().filter(r -> !inside(g, r.site(), type.id()))
                .map(r -> g.element(r.site()).qualified()).distinct().toList();
        if (!uses.isEmpty()) throw new PrimitiveRejected("delete.referenced", type.qualified() + " is used by " + uses);
        var path = type.fact(Fact.FILE);
        if (path == null) throw new IllegalArgumentException("only top-level types can be deleted so far: " + type);
        var edits = new LinkedHashMap<String, String>();
        units.forEach((p, cu) -> cu.getImports().stream()
                .filter(i -> !i.isAsterisk() && !i.isStatic() && i.getNameAsString().equals(type.qualified()))
                .findFirst().ifPresent(i -> edits.put(p, Imports.remove(source(p), cu, i))));
        units.remove(path);
        parsedText.remove(path);
        edits.forEach((p, code) -> units.put(p, parse(p, code)));
        invalidate();
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
                        var decl = n.resolve();
                        return decl.isParameter();
                    } catch (RuntimeException unresolved) {
                        return false;
                    }
                });
        if (used) throw new PrimitiveRejected("delete.referenced", param.qualified() + " is used in the body of " + method.qualified());
        var index = declaration.getParameters().indexOf(declaration.getParameterByName(param.name()).orElseThrow());
        var edits = new LinkedHashMap<String, List<Map.Entry<Node, String>>>();
        remove(edits, declaration.getParameters(), index);
        for (var call : callsTo(method.id())) remove(edits, call.getArguments(), index);
        var names = new ArrayList<>(declaration.getParameters().stream().map(p -> p.getNameAsString()).toList());
        names.remove(index);
        applyEdits(edits);
        ids.forget(param.id());
        resign(method, names);
    }

    private void reorder(Map<String, List<Map.Entry<Node, String>>> edits, NodeList<? extends Node> items, int from, int to) {
        var texts = new ArrayList<>(items.stream().map(n -> LexicalPreservingPrinter.print(n)).toList());
        texts.add(to, texts.remove(from));
        rewriteList(edits, items, texts);
    }

    private void remove(Map<String, List<Map.Entry<Node, String>>> edits, NodeList<? extends Node> items, int index) {
        var texts = new ArrayList<>(items.stream().map(n -> LexicalPreservingPrinter.print(n)).toList());
        texts.remove(index);
        rewriteList(edits, items, texts);
    }

    /** Replaces the text from the first item to the last with {@code texts} joined by ", ". */
    private void rewriteList(Map<String, List<Map.Entry<Node, String>>> edits, NodeList<? extends Node> items, List<String> texts) {
        var first = items.getFirst().orElseThrow();
        var last = items.getLast().orElseThrow();
        var span = new NameExpr("span");
        span.setRange(new com.github.javaparser.Range(first.getBegin().orElseThrow(), last.getEnd().orElseThrow()));
        var path = pathOf(first.findCompilationUnit().orElseThrow());
        edits.computeIfAbsent(path, k -> new ArrayList<>()).add(Map.entry(span, String.join(", ", texts)));
    }

    /** Applies text replacements per unit, last position first so earlier positions stay valid; re-parses. */
    private void applyEdits(Map<String, List<Map.Entry<Node, String>>> edits) {
        edits.forEach((path, list) -> {
            var code = source(path);
            var sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparing((Map.Entry<Node, String> e) -> e.getKey().getBegin().orElseThrow()).reversed());
            for (var edit : sorted) code = Text.replace(code, edit.getKey(), edit.getValue());
            units.put(path, parse(path, code));
        });
        invalidate();
    }

    /** After a parameter list change, the method's identity follows its re-parsed declaration (found by its parameter names). */
    private void resign(Element method, List<String> parameterNames) {
        var callable = (Address.Callable) ids.address(method.id());
        var declaration = typeDeclaration(ids.render(ids.address(callable.owner()))).getMethodsByName(method.name()).stream()
                .filter(m -> m.getParameters().stream().map(p -> p.getNameAsString()).toList().equals(parameterNames))
                .findFirst().orElseThrow();
        ids.resign(method.id(), keys.of(declaration));
    }

    /** Adds an import to the unit of a top-level type. */
    private void addImport(ElementId id, String qualified) {
        var cu = typeDeclaration(program().element(id).qualified()).findCompilationUnit().orElseThrow();
        var path = pathOf(cu);
        load(path, Imports.add(source(path), cu, qualified));
    }

    private void setType(ElementId id, String type) {
        var element = program().element(id);
        if (type.contains(".") && ids.has(new Address.Type(type))) type = importing(element, type); // a workspace type by name
        var parsed = parser.parseType(type).getResult().orElseThrow();
        switch (element.kind()) {
            case FIELD -> ((FieldDeclaration) declaration(element)).getVariables().forEach(v -> v.setType(parsed.clone()));
            case METHOD -> ((MethodDeclaration) declaration(element)).setType(parsed);
            case PARAM -> editMethod(program().element(element.parent()),
                    m -> m.getParameterByName(element.name()).orElseThrow().setType(parsed));
            default -> throw new IllegalArgumentException("unsupported type change: " + element);
        }
    }

    /** Replaces a method body with a one-statement-per-line template; {@code #{id}} renders as the element's name. */
    private void setBody(ElementId id, String template) {
        var method = (MethodDeclaration) declaration(program().element(id));
        var body = method.getBody().orElseThrow();
        var indent = " ".repeat(method.getBegin().orElseThrow().column - 1);
        var statements = render(template).lines().map(line -> indent + "    " + line + "\n").collect(Collectors.joining());
        replaceText(body, "{\n" + statements + indent + "}");
    }

    private String render(String template) {
        var matcher = java.util.regex.Pattern.compile("#\\{([^}]+)}").matcher(template);
        return matcher.replaceAll(m -> program().element(new ElementId(m.group(1))).name());
    }

    /** Sets the superclass by its qualified name, written as a simple name when in the same package. */
    private void setSuperclass(ElementId id, String superclass) {
        var type = (ClassOrInterfaceDeclaration) typeDeclaration(program().element(id).qualified());
        var pkg = type.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString() + ".").orElse("");
        var name = superclass.startsWith(pkg) && superclass.indexOf('.', pkg.length()) < 0
                ? superclass.substring(pkg.length()) : superclass;
        if (type.getExtendedTypes().isEmpty()) type.addExtendedType(name);
        else type.getExtendedTypes(0).setName(name);
    }

    /**
     * A method without body (in an interface just the signature, in a class an abstract method), or an abstract
     * class.
     */
    private void setAbstract(ElementId id, boolean on) {
        var element = program().element(id);
        if (!on) throw new IllegalArgumentException("unsupported: abstract=false on " + element);
        if (element.kind() == Kind.CLASS) {
            setFlag(id, Modifier.Keyword.ABSTRACT, true);
            return;
        }
        if (element.kind() != Kind.METHOD) throw new IllegalArgumentException("unsupported: abstract on " + element);
        if (program().element(element.parent()).kind() != Kind.INTERFACE) {
            setFlag(id, Modifier.Keyword.ABSTRACT, true);
            changed(); // positions must match the text again before the next edit
        }
        var method = (MethodDeclaration) declaration(program().element(id));
        var signature = LexicalPreservingPrinter.print(method).strip();
        replaceText(method, signature.substring(0, signature.indexOf('{')).stripTrailing() + ";");
    }

    /** Adds an implemented interface by qualified name; simple name when in the same package. */
    private void addImplements(ElementId id, String qualified) {
        var type = (ClassOrInterfaceDeclaration) typeDeclaration(program().element(id).qualified());
        var pkg = type.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString() + ".").orElse("");
        var name = qualified.startsWith(pkg) && qualified.indexOf('.', pkg.length()) < 0 ? qualified.substring(pkg.length()) : qualified;
        type.addImplementedType(name);
    }

    /** Adds or removes a modifier keyword, after the access modifier. */
    private void setFlag(ElementId id, Modifier.Keyword keyword, boolean on) {
        var modifiers = ((NodeWithModifiers<?>) declaration(program().element(id))).getModifiers();
        modifiers.removeIf(m -> m.getKeyword() == keyword);
        if (on) {
            var index = (int) modifiers.stream().filter(m -> ACCESS.contains(m.getKeyword())).count();
            modifiers.add(index, new Modifier(keyword));
        }
    }

    private void setVisibility(ElementId id, String value) {
        var modifiers = ((NodeWithModifiers<?>) declaration(program().element(id))).getModifiers();
        modifiers.removeIf(m -> ACCESS.contains(m.getKeyword()));
        if (!value.equals("package")) modifiers.addFirst(new Modifier(Modifier.Keyword.valueOf(value.toUpperCase())));
    }

    private ElementId extract(ElementId from, String fragment, String name) {
        requireIdentifier(name);
        var source = program().element(from);
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
        var path = pathOf(method.findCompilationUnit().orElseThrow());
        var code = source(path);
        var begin = selected.getFirst().getBegin().orElseThrow().line;
        var end = selected.getLast().getEnd().orElseThrow().line;
        var callIndent = code.substring(Text.lineStart(code, begin)).replaceFirst("(?s)^([ \\t]*).*", "$1");
        var body = Bodies.reindent(code.substring(Text.lineStart(code, begin), Text.lineStart(code, end + 1)), " ".repeat(8));
        if (output != null) body += " ".repeat(8) + "return " + output + ";\n";
        var call = name + "(" + String.join(", ", flow.inputs().keySet()) + ")";
        var callLine = output == null ? call + ";"
                : flow.declaredInside(output) ? outputType + " " + output + " = " + call + ";"
                : output + " = " + call + ";";
        var edited = code.substring(0, Text.lineStart(code, begin)) + callIndent + callLine + "\n" + code.substring(Text.lineStart(code, end + 1));
        load(path, edited);
        var owner = program().element(source.parent());
        var modifiers = method.isStatic() ? "private static " : "private ";
        insertMember(typeDeclaration(owner.qualified()), modifiers + outputType + " " + name + "(" + params + ") {\n" + body + "    }");
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
        var body = original.clone();
        if (thisMembers && call.getScope().filter(scope -> !scope.isThisExpr()).isPresent()) {
            throw new PrimitiveRejected("inline.receiver", method.qualified() + " uses members of this; the call has another receiver");
        }
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
        var path = pathOf(call.findCompilationUnit().orElseThrow());
        load(path, Text.replace(source(path), call, text));
    }

    /**
     * Retargets a field use to an accessor: a read becomes {@code getter()}, a write {@code x = v} used as a statement
     * becomes {@code setter(v)}. Updates (compound assignment, ++/--) and assignments whose value is used are rejected:
     * they would change how many times things are evaluated.
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
        if (target.kind() == program().element(ref.target()).kind()) { // same kind: the use just names the other one
            switch (referenceNodes.get(refId)) {
                case MethodReferenceExpr reference -> reference.setIdentifier(target.name());
                case NodeWithSimpleName<?> named -> named.setName(target.name());
                default -> throw new IllegalStateException("cannot rename the use " + refId);
            }
            changed();
            return;
        }
        if (target.kind() != Kind.METHOD) throw new IllegalArgumentException("can only retarget to methods: " + target);
        var scope = node.isFieldAccessExpr() ? node.asFieldAccessExpr().getScope() : null;
        if (node.isObjectCreationExpr()) scope = null;
        switch (ref.mode()) {
            case READ -> node.replace(new MethodCallExpr(scope, target.name()));
            case NEW -> {
                var creation = (ObjectCreationExpr) node;
                if (creation.getAnonymousClassBody().isPresent()) {
                    throw new PrimitiveRejected("retarget.anonymous-class", "cannot turn " + creation + " into a call");
                }
                creation.replace(new MethodCallExpr(new NameExpr(creation.getType().asString()), target.name(),
                        creation.getArguments()));
            }
            case WRITE -> {
                var assign = (AssignExpr) node.getParentNode().orElseThrow();
                if (!(assign.getParentNode().orElseThrow() instanceof ExpressionStmt)) {
                    throw new PrimitiveRejected("retarget.value-used", "the value of " + assign + " is used");
                }
                assign.replace(new MethodCallExpr(scope, target.name(), new NodeList<>(assign.getValue())));
            }
            default -> throw new PrimitiveRejected("retarget." + ref.mode().label(),
                    "cannot express " + node.getParentNode().orElseThrow() + " with " + target.qualified());
        }
        changed();
    }

    /** Makes {@code qualified} nameable by its simple name where {@code element} is declared; returns that name. */
    private String importing(Element element, String qualified) {
        var unit = typeDeclaration(program().topLevel(element.id()).qualified()).findCompilationUnit().orElseThrow();
        var unitPackage = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        var imported = unit.getImports().stream().anyMatch(i -> i.getNameAsString().equals(qualified));
        if (!packageOf(qualified).equals(unitPackage) && !imported) {
            var path = pathOf(unit);
            load(path, Imports.add(source(path), unit, qualified));
        }
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** A type use now names {@code type}: its simple name, imported when another package declares it. */
    private void retargetType(Reference ref, Element type) {
        var node = referenceNodes.get(ref.id());
        var cu = node.findCompilationUnit().orElseThrow();
        var path = pathOf(cu);
        var nameNode = node instanceof ClassOrInterfaceType t ? t.getName() : node;
        var code = Text.replace(source(path), nameNode, type.name());
        var unitPackage = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        var imported = cu.getImports().stream().anyMatch(i -> i.getNameAsString().equals(type.qualified()));
        if (!packageOf(type.qualified()).equals(unitPackage) && !imported) code = Imports.add(code, parse(code), type.qualified());
        load(path, code);
    }

    // ---- AST lookup and text edits

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
        return units.values().stream()
                .flatMap(cu -> types(cu).stream())
                .filter(t -> t.getFullyQualifiedName().map(Object::toString).orElse("").equals(qualified))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no type " + qualified));
    }

    /** Inserts a member as a text edit just before the type's closing brace, then re-parses the unit. */
    private void insertMember(TypeDeclaration<?> type, String memberText) {
        var path = pathOf(type.findCompilationUnit().orElseThrow());
        var code = source(path);
        var offset = Text.lineStart(code, type.getEnd().orElseThrow().line);
        var indent = " ".repeat(4);
        load(path, code.substring(0, offset) + "\n" + indent + memberText + "\n" + code.substring(offset));
    }

    /** Inserts a field after the last field, or first in the body followed by a blank line; re-parses. */
    private void insertField(TypeDeclaration<?> type, String fieldText) {
        var path = pathOf(type.findCompilationUnit().orElseThrow());
        var code = source(path);
        var indent = " ".repeat(4);
        var fields = type.getFields();
        if (fields.isEmpty()) {
            var offset = Text.lineStart(code, type.getName().getEnd().orElseThrow().line + 1);
            var separate = type.getMembers().isEmpty() ? "" : "\n";
            load(path, code.substring(0, offset) + indent + fieldText + "\n" + separate + code.substring(offset));
        } else {
            var offset = Text.lineStart(code, fields.get(fields.size() - 1).getEnd().orElseThrow().line + 1);
            load(path, code.substring(0, offset) + indent + fieldText + "\n" + code.substring(offset));
        }
    }

    /** Inserts a member as a text edit on the lines just above {@code anchor}, then re-parses the unit. */
    private void insertBefore(Node anchor, String memberText) {
        var path = pathOf(anchor.findCompilationUnit().orElseThrow());
        var code = source(path);
        var offset = Text.lineStart(code, anchor.getBegin().orElseThrow().line);
        var indent = " ".repeat(4);
        load(path, code.substring(0, offset) + indent + memberText + "\n\n" + code.substring(offset));
    }

    /** Replaces the text of a node, then re-parses its unit. */
    private void replaceText(Node node, String text) {
        var path = pathOf(node.findCompilationUnit().orElseThrow());
        load(path, Text.replace(source(path), node, text));
    }

    private void load(String path, String code) {
        units.put(path, parse(path, code));
        invalidate();
    }

    /** Parses a unit and remembers the text it came from, to know later whether it changed. */
    private CompilationUnit parse(String path, String code) {
        var cu = parse(code);
        parsedText.put(path, code);
        return cu;
    }

    private CompilationUnit parse(String code) {
        var result = parser.parse(code);
        if (!result.isSuccessful()) throw new IllegalStateException("unparsable result: " + result.getProblems() + "\n" + code);
        return LexicalPreservingPrinter.setup(result.getResult().orElseThrow());
    }

    /**
     * After an in-place AST edit: re-parses every unit from its text, so positions match the text again and no
     * resolution runs on an edited AST.
     */
    private void changed() {
        units.replaceAll((path, cu) -> {
            var text = LexicalPreservingPrinter.print(cu);
            return text.equals(parsedText.get(path)) ? cu : parse(path, text);
        });
        invalidate();
    }

    private void invalidate() {
        JavaParserFacade.clearInstances(); // resolution caches point into replaced ASTs
        units.values().forEach(JavaProject::forgetResolvedTypes);
        typeSolver.reset();
        graph = null;
        referenceNodes = Map.of();
        diagnostics = null;
    }

    /**
     * The facade also caches resolved types on the nodes themselves; in units that were not re-parsed they would
     * still describe the program before the change (e.g. a field whose declared type changed elsewhere).
     */
    private static void forgetResolvedTypes(CompilationUnit cu) {
        cu.walk(node -> {
            for (var key : List.copyOf(node.getDataKeys())) {
                if (key.getClass().getName().startsWith(JavaParserFacade.class.getName() + "$")) node.removeData(key);
            }
        });
    }

    private String pathOf(CompilationUnit cu) {
        return units.entrySet().stream().filter(e -> e.getValue() == cu)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

}
