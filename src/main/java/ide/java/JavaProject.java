package ide.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import ide.graph.PrimitiveRejected;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import ide.graph.CompletionContext;
import ide.graph.Diagnostic;
import ide.graph.Element;
import ide.graph.ElementId;
import ide.graph.Kind;
import ide.graph.Program;
import ide.graph.ProgramGraph;
import ide.graph.Reference;

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
 * Java sources in memory, edited with lexical preservation, projected to a {@link ProgramGraph}.
 * Identity is kept in {@code ids} (address → id): parsing never decides identity, primitives transfer it.
 */
public final class JavaProject implements Program {

    /** Everything needed to roll back: source text and identity. */
    public record Snapshot(Map<String, String> sources, Map<String, ElementId> ids, int nextId) {
    }

    private static final Set<Modifier.Keyword> ACCESS =
            Set.of(Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.PRIVATE);

    private final JavaParser parser;
    private final WorkspaceTypeSolver typeSolver;
    private final Map<String, CompilationUnit> units = new TreeMap<>();
    private Map<String, ElementId> ids = new HashMap<>();
    private int nextId;
    private ProgramGraph graph;
    private Map<String, Node> referenceNodes = Map.of();
    private List<Diagnostic> diagnostics;
    private final Map<String, String> parsedText = new HashMap<>();

    private JavaProject() {
        var solver = new WorkspaceTypeSolver(units::values, new ReflectionTypeSolver());
        typeSolver = solver;
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

    public Snapshot snapshot() {
        var sources = new LinkedHashMap<String, String>();
        units.keySet().forEach(path -> sources.put(path, source(path)));
        return new Snapshot(sources, Map.copyOf(ids), nextId);
    }

    public void restore(Snapshot snapshot) {
        units.clear();
        parsedText.clear();
        snapshot.sources().forEach(this::load);
        ids = new HashMap<>(snapshot.ids());
        nextId = snapshot.nextId();
        invalidate();
    }

    @Override
    public Object checkpoint() {
        return snapshot();
    }

    @Override
    public void rollback(Object checkpoint) {
        restore((Snapshot) checkpoint);
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
        graph(); // identities of the saved version, which the edited text is matched against
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
        var siteId = siteOf(site.get());
        if (siteId == null) return Optional.empty();
        try {
            String receiver = site.get() instanceof FieldAccessExpr access
                    ? access.getScope().calculateResolvedType().asReferenceType().getQualifiedName()
                    : site.get().findAncestor(TypeDeclaration.class).orElseThrow().getFullyQualifiedName().orElseThrow().toString();
            return Optional.ofNullable(ids.get(receiver)).map(r -> new CompletionContext(r, prefix, siteId));
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    @Override
    public ProgramGraph graph() {
        if (graph == null) graph = buildGraph();
        return graph;
    }

    // ---- primitives

    /**
     * Creates an empty element: a top-level class in its own unit, a {@code void} method without parameters at the
     * end of a type, or an {@code Object} parameter at the end of a method.
     */
    @Override
    public ElementId create(Kind kind, ElementId parent, String name) {
        requireIdentifier(name);
        var owner = graph().element(parent);
        return switch (kind) {
            case CLASS -> {
                if (owner.kind() != Kind.PACKAGE) throw new IllegalArgumentException("classes go in packages: " + owner);
                var path = owner.qualified().replace('.', '/') + "/" + name + ".java";
                if (units.containsKey(path)) throw new IllegalArgumentException("already exists: " + path);
                load(path, "package " + owner.qualified() + ";\n\npublic class " + name + " {\n}\n");
                yield idOf(owner.qualified() + "." + name, Kind.CLASS);
            }
            case INTERFACE -> {
                if (owner.kind() != Kind.PACKAGE) throw new IllegalArgumentException("interfaces go in packages: " + owner);
                var path = owner.qualified().replace('.', '/') + "/" + name + ".java";
                if (units.containsKey(path)) throw new IllegalArgumentException("already exists: " + path);
                load(path, "package " + owner.qualified() + ";\n\npublic interface " + name + " {\n}\n");
                yield idOf(owner.qualified() + "." + name, Kind.CLASS);
            }
            case FIELD -> {
                insertField(typeDeclaration(owner.qualified()), "Object " + name + ";");
                yield idOf(owner.qualified() + "#" + name, Kind.FIELD);
            }
            case METHOD -> {
                insertMember(typeDeclaration(owner.qualified()), "void " + name + "() {\n" + " ".repeat(4) + "}");
                yield idOf(owner.qualified() + "#" + name + "()", Kind.METHOD);
            }
            case PARAM -> {
                // calls get a placeholder argument until the parameter's default is set
                var calls = callsTo(owner.qualified());
                var marker = new NameExpr(defaultMarker(owner.name(), name));
                var method = editMethod(owner, m -> {
                    m.addParameter("Object", name);
                    calls.forEach(call -> call.addArgument(marker.clone()));
                });
                yield idOf(method + "/" + name, Kind.PARAM);
            }
            default -> throw new IllegalArgumentException("unsupported create: " + kind);
        };
    }

    private static void requireIdentifier(String name) {
        if (!javax.lang.model.SourceVersion.isName(name) || name.contains(".")) {
            throw new PrimitiveRejected("projection.invalid-identifier", "'" + name + "' is not a Java identifier");
        }
    }

    /** Edits a method's declaration and carries its identity (and its parameters') to its new address. */
    private String editMethod(Element method, java.util.function.Consumer<MethodDeclaration> edit) {
        var node = (MethodDeclaration) declaration(method);
        edit.accept(node);
        var address = methodAddress(method.qualified().substring(0, method.qualified().indexOf('#')), node);
        changed();
        transfer(method.qualified(), address);
        return address;
    }

    /** Moves a member into another type, keeping its identity. */
    @Override
    public void move(ElementId member, ElementId newParent, ElementId before) {
        var g = graph();
        var element = g.element(member);
        var target = g.element(newParent);
        if (target.kind() == Kind.PACKAGE) {
            moveType(element, target);
            return;
        }
        var anchor = before == null ? null : g.element(before);
        var signature = element.qualified().substring(element.qualified().indexOf('#'));
        if (ids.containsKey(target.qualified() + signature)) {
            throw new PrimitiveRejected("projection.duplicate", target.qualified() + signature + " already exists");
        }
        var node = declaration(element);
        var text = LexicalPreservingPrinter.print(node).strip();
        node.remove();
        changed();
        if (anchor == null) insertMember(typeDeclaration(target.qualified()), text);
        else insertBefore(declaration(anchor), text);
        transfer(element.qualified(), target.qualified() + signature);
    }

    /**
     * Moves a top-level type to another package: new file and package line; imports for what it used from its old
     * package; in other units the import is rewritten, added (old package users) or dropped (new package users).
     */
    private void moveType(Element type, Element pkg) {
        var oldPackage = packageOf(type.qualified());
        var newQualified = pkg.qualified() + "." + type.name();
        var oldPath = type.attribute("file");
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
        var edits = new LinkedHashMap<String, String>();
        for (var entry : units.entrySet()) {
            var path = entry.getKey();
            var cu = entry.getValue();
            if (path.equals(oldPath)) continue;
            var unitPackage = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            var imported = cu.getImports().stream().filter(i -> !i.isAsterisk() && !i.isStatic()
                    && i.getNameAsString().equals(type.qualified())).findFirst();
            var uses = cu.findAll(ClassOrInterfaceType.class).stream().anyMatch(t -> type.qualified().equals(resolveType(t)));
            var code = source(path);
            if (imported.isPresent() && unitPackage.equals(pkg.qualified())) edits.put(path, Imports.remove(code, cu, imported.get()));
            else if (imported.isPresent()) edits.put(path, Imports.replace(code, imported.get(), newQualified));
            else if (uses && unitPackage.equals(oldPackage)) edits.put(path, Imports.add(code, cu, newQualified));
        }

        var code = source(oldPath);
        for (var qualified : needed) code = Imports.add(code, parse(code), qualified);
        var packageLine = parse(code).getPackageDeclaration().orElseThrow();
        code = Text.replace(code, packageLine, "package " + pkg.qualified() + ";");

        units.remove(oldPath);
        parsedText.remove(oldPath);
        units.put(newPath, parse(newPath, code));
        edits.forEach((path, edited) -> units.put(path, parse(path, edited)));
        invalidate();
        transfer(type.qualified(), newQualified);
    }

    private static String packageOf(String qualifiedType) {
        var dot = qualifiedType.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedType.substring(0, dot);
    }

    /**
     * Deletes a member (with its comment) that nothing references: its lines go, and a blank line left doubled
     * or against a brace goes with them.
     */
    @Override
    public void delete(ElementId id) {
        var g = graph();
        var element = g.element(id);
        if (element.kind() == Kind.PARAM) {
            deleteParam(element);
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
        ids.remove(element.qualified());
    }

    /** Renames a field or method together with every use that resolves to it, keeping its identity. */
    @Override
    public void rename(ElementId id, String name) {
        requireIdentifier(name);
        var element = graph().element(id);
        var uses = usesOf(element.qualified());
        var node = declaration(element);
        switch (node) {
            case com.github.javaparser.ast.body.Parameter param -> param.setName(name);
            case FieldDeclaration field -> field.getVariables().stream()
                    .filter(v -> v.getNameAsString().equals(element.name())).forEach(v -> v.setName(name));
            case MethodDeclaration method -> method.setName(name);
            default -> throw new IllegalArgumentException("unsupported rename: " + element);
        }
        var owner = element.qualified().substring(0, element.qualified().indexOf('#') + 1);
        var rest = element.qualified().substring(owner.length() + element.name().length());
        if (ids.containsKey(owner + name + rest)) {
            throw new PrimitiveRejected("projection.duplicate", owner + name + rest + " already exists");
        }
        uses.forEach(use -> use.setName(name));
        transfer(element.qualified(), owner + name + rest);
        changed();
    }

    /** Expressions anywhere in the workspace that use the element at {@code address}, from the current graph. */
    private List<NodeWithSimpleName<?>> usesOf(String address) {
        var g = graph();
        var id = ids.get(address);
        var uses = new ArrayList<NodeWithSimpleName<?>>();
        for (var ref : g.referencesTo(id)) {
            if (referenceNodes.get(ref.id()) instanceof NodeWithSimpleName<?> named) uses.add(named);
        }
        return uses;
    }

    @Override
    public void replaceSource(String path, String text) {
        var result = parser.parse(text);
        if (!result.isSuccessful()) {
            throw new PrimitiveRejected("projection.unparsable", path + ": " + result.getProblems().getFirst().getVerboseMessage());
        }
        load(path, text);
    }

    /** Changes an attribute of an element: {@code visibility} of a member, {@code superclass} of a class. */
    @Override
    public void set(ElementId id, String attribute, String value) {
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
            case "initializer" -> ((FieldDeclaration) declaration(graph().element(id))).getVariables().stream()
                    .filter(v -> v.getNameAsString().equals(graph().element(id).name()))
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

    /** Calls anywhere in the workspace that resolve to the method at {@code address}, from the current graph. */
    private List<MethodCallExpr> callsTo(String address) {
        var g = graph();
        var calls = new ArrayList<MethodCallExpr>();
        for (var ref : g.referencesTo(ids.get(address))) {
            if (referenceNodes.get(ref.id()) instanceof MethodCallExpr call) calls.add(call);
        }
        return calls;
    }

    /** The argument the calls pass for a parameter created in this transaction. */
    private void setDefault(ElementId id, String value) {
        var param = graph().element(id);
        var marker = defaultMarker(graph().element(param.parent()).name(), param.name());
        var edits = new LinkedHashMap<String, List<Map.Entry<Node, String>>>();
        units.forEach((path, cu) -> cu.findAll(NameExpr.class, n -> n.getNameAsString().equals(marker))
                .forEach(n -> edits.computeIfAbsent(path, k -> new ArrayList<>()).add(Map.entry(n, value))));
        applyEdits(edits);
    }

    /** Moves a parameter to position {@code index}, in the declaration and in every call. */
    private void setOrder(ElementId id, int index) {
        var param = graph().element(id);
        var method = graph().element(param.parent());
        var declaration = (MethodDeclaration) declaration(method);
        var from = declaration.getParameters().indexOf(declaration.getParameterByName(param.name()).orElseThrow());
        var edits = new LinkedHashMap<String, List<Map.Entry<Node, String>>>();
        reorder(edits, declaration.getParameters(), from, index);
        for (var call : callsTo(method.qualified())) reorder(edits, call.getArguments(), from, index);
        var names = new ArrayList<>(declaration.getParameters().stream().map(p -> p.getNameAsString()).toList());
        names.add(index, names.remove(from));
        applyEdits(edits);
        retransferMethod(method, names);
    }

    /** Removes a parameter the body does not use, and the matching argument from every call. */
    private void deleteParam(Element param) {
        var method = graph().element(param.parent());
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
        for (var call : callsTo(method.qualified())) remove(edits, call.getArguments(), index);
        var names = new ArrayList<>(declaration.getParameters().stream().map(p -> p.getNameAsString()).toList());
        names.remove(index);
        applyEdits(edits);
        ids.remove(param.qualified());
        retransferMethod(method, names);
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

    /** After a parameter list change, carries the method's identity to the address of its new declaration. */
    private void retransferMethod(Element method, List<String> parameterNames) {
        var owner = method.qualified().substring(0, method.qualified().indexOf('#'));
        var declaration = typeDeclaration(owner).getMethodsByName(method.name()).stream()
                .filter(m -> m.getParameters().stream().map(p -> p.getNameAsString()).toList().equals(parameterNames))
                .findFirst().orElseThrow();
        transfer(method.qualified(), methodAddress(owner, declaration));
    }

    /** Adds an import to the unit of a top-level type. */
    private void addImport(ElementId id, String qualified) {
        var cu = typeDeclaration(graph().element(id).qualified()).findCompilationUnit().orElseThrow();
        var path = pathOf(cu);
        load(path, Imports.add(source(path), cu, qualified));
    }

    private void setType(ElementId id, String type) {
        var element = graph().element(id);
        var parsed = parser.parseType(type).getResult().orElseThrow();
        switch (element.kind()) {
            case FIELD -> ((FieldDeclaration) declaration(element)).getVariables().forEach(v -> v.setType(parsed.clone()));
            case METHOD -> ((MethodDeclaration) declaration(element)).setType(parsed);
            case PARAM -> editMethod(graph().element(element.parent()),
                    m -> m.getParameterByName(element.name()).orElseThrow().setType(parsed));
            default -> throw new IllegalArgumentException("unsupported type change: " + element);
        }
    }

    /** Replaces a method body with a one-statement-per-line template; {@code #{id}} renders as the element's name. */
    private void setBody(ElementId id, String template) {
        var method = (MethodDeclaration) declaration(graph().element(id));
        var body = method.getBody().orElseThrow();
        var indent = " ".repeat(method.getBegin().orElseThrow().column - 1);
        var statements = render(template).lines().map(line -> indent + "    " + line + "\n").collect(Collectors.joining());
        replaceText(body, "{\n" + statements + indent + "}");
    }

    private String render(String template) {
        var matcher = java.util.regex.Pattern.compile("#\\{([^}]+)}").matcher(template);
        return matcher.replaceAll(m -> graph().element(new ElementId(m.group(1))).name());
    }

    /** Sets the superclass by its qualified name, written as a simple name when in the same package. */
    private void setSuperclass(ElementId id, String superclass) {
        var type = (ClassOrInterfaceDeclaration) typeDeclaration(graph().element(id).qualified());
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
        var element = graph().element(id);
        if (!on) throw new IllegalArgumentException("unsupported: abstract=false on " + element);
        if (element.kind() == Kind.CLASS) {
            setFlag(id, Modifier.Keyword.ABSTRACT, true);
            return;
        }
        if (element.kind() != Kind.METHOD) throw new IllegalArgumentException("unsupported: abstract on " + element);
        if (graph().element(element.parent()).kind() != Kind.INTERFACE) {
            setFlag(id, Modifier.Keyword.ABSTRACT, true);
            changed(); // positions must match the text again before the next edit
        }
        var method = (MethodDeclaration) declaration(graph().element(id));
        var signature = LexicalPreservingPrinter.print(method).strip();
        replaceText(method, signature.substring(0, signature.indexOf('{')).stripTrailing() + ";");
    }

    /** Adds an implemented interface by qualified name; simple name when in the same package. */
    private void addImplements(ElementId id, String qualified) {
        var type = (ClassOrInterfaceDeclaration) typeDeclaration(graph().element(id).qualified());
        var pkg = type.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString() + ".").orElse("");
        var name = qualified.startsWith(pkg) && qualified.indexOf('.', pkg.length()) < 0 ? qualified.substring(pkg.length()) : qualified;
        type.addImplementedType(name);
    }

    /** Adds or removes a modifier keyword, after the access modifier. */
    private void setFlag(ElementId id, Modifier.Keyword keyword, boolean on) {
        var modifiers = ((NodeWithModifiers<?>) declaration(graph().element(id))).getModifiers();
        modifiers.removeIf(m -> m.getKeyword() == keyword);
        if (on) {
            var index = (int) modifiers.stream().filter(m -> ACCESS.contains(m.getKeyword())).count();
            modifiers.add(index, new Modifier(keyword));
        }
    }

    private void setVisibility(ElementId id, String value) {
        var modifiers = ((NodeWithModifiers<?>) declaration(graph().element(id))).getModifiers();
        modifiers.removeIf(m -> ACCESS.contains(m.getKeyword()));
        if (!value.equals("package")) modifiers.addFirst(new Modifier(Modifier.Keyword.valueOf(value.toUpperCase())));
    }

    @Override
    public ElementId extract(ElementId from, String fragment, String name) {
        requireIdentifier(name);
        var source = graph().element(from);
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
        var owner = graph().element(source.parent());
        var modifiers = method.isStatic() ? "private static " : "private ";
        insertMember(typeDeclaration(owner.qualified()), modifiers + outputType + " " + name + "(" + params + ") {\n" + body + "    }");
        var created = typeDeclaration(owner.qualified()).getMethodsByName(name).getFirst();
        return idOf(methodAddress(owner.qualified(), created), Kind.METHOD);
    }

    /**
     * Inlines a call to a method whose body is a single {@code return expr;}: each parameter becomes its argument
     * (parenthesized where precedence needs it), and so does the whole expression at the call site. Refused:
     * recursion, a body using implicit {@code this} members called on another receiver, and a non-trivial argument
     * whose parameter is not used exactly once (it would be evaluated another number of times).
     */
    @Override
    public void inline(String refId) {
        var ref = graph().reference(refId);
        if (!(referenceNodes.get(refId) instanceof MethodCallExpr call)) {
            throw new PrimitiveRejected("inline.not-a-call", refId + " is not a call");
        }
        var method = graph().element(ref.target());
        var declaration = (MethodDeclaration) declaration(method);
        var statements = declaration.getBody().map(b -> b.getStatements()).orElseThrow();
        if (statements.size() != 1 || !statements.get(0).isReturnStmt() || statements.get(0).asReturnStmt().getExpression().isEmpty()) {
            throw new PrimitiveRejected("inline.body-shape", method.qualified() + " is not a single 'return expr;'");
        }
        var body = statements.get(0).asReturnStmt().getExpression().get().clone();
        if (body.findAll(MethodCallExpr.class).stream().anyMatch(c -> method.qualified().equals(resolveMethod(c)))) {
            throw new PrimitiveRejected("inline.recursive", method.qualified() + " calls itself");
        }
        var thisMembers = body.findAll(NameExpr.class).stream().anyMatch(n -> resolveField(n) != null)
                || body.findAll(MethodCallExpr.class).stream().anyMatch(c -> c.getScope().isEmpty());
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
    @Override
    public void retarget(String refId, ElementId targetId) {
        var ref = graph().reference(refId);
        var node = (Expression) referenceNodes.get(refId);
        if (node.isObjectCreationExpr() && !ref.mode().equals("new")) throw new IllegalStateException(ref.toString());
        var target = graph().element(targetId);
        if (target.kind() != Kind.METHOD) throw new IllegalArgumentException("can only retarget to methods: " + target);
        var scope = node.isFieldAccessExpr() ? node.asFieldAccessExpr().getScope() : null;
        if (node.isObjectCreationExpr()) scope = null;
        switch (ref.mode()) {
            case "read" -> node.replace(new MethodCallExpr(scope, target.name()));
            case "new" -> {
                var creation = (ObjectCreationExpr) node;
                if (creation.getAnonymousClassBody().isPresent()) {
                    throw new PrimitiveRejected("retarget.anonymous-class", "cannot turn " + creation + " into a call");
                }
                creation.replace(new MethodCallExpr(new NameExpr(creation.getType().asString()), target.name(),
                        creation.getArguments()));
            }
            case "write" -> {
                var assign = (AssignExpr) node.getParentNode().orElseThrow();
                if (!(assign.getParentNode().orElseThrow() instanceof ExpressionStmt)) {
                    throw new PrimitiveRejected("retarget.value-used", "the value of " + assign + " is used");
                }
                assign.replace(new MethodCallExpr(scope, target.name(), new NodeList<>(assign.getValue())));
            }
            default -> throw new PrimitiveRejected("retarget." + ref.mode(),
                    "cannot express " + node.getParentNode().orElseThrow() + " with " + target.qualified());
        }
        changed();
    }

    // ---- projection: graph from AST

    private ProgramGraph buildGraph() {
        var elements = new ArrayList<Element>();
        var references = new ArrayList<Reference>();
        var nodes = new HashMap<String, Node>();
        var packages = new LinkedHashMap<String, ElementId>();
        for (var cu : units.values()) {
            var pkg = cu.getPackageDeclaration().map(p -> {
                var name = p.getNameAsString();
                return packages.computeIfAbsent(name, n -> {
                    var id = idOf(n, Kind.PACKAGE);
                    elements.add(new Element(id, Kind.PACKAGE, n, null, n, Map.of()));
                    return id;
                });
            }).orElse(null);
            for (TypeDeclaration<?> type : types(cu)) {
                var typeName = type.getFullyQualifiedName().orElseThrow();
                var parent = type.getParentNode().filter(TypeDeclaration.class::isInstance)
                        .map(t -> idOf(((TypeDeclaration<?>) t).getFullyQualifiedName().orElseThrow(), Kind.CLASS)).orElse(pkg);
                var typeId = idOf(typeName, Kind.CLASS);
                var typeAttributes = new HashMap<>(typeAttributes(type));
                if (type.isTopLevelType()) typeAttributes.put("file", pathOf(cu));
                elements.add(new Element(typeId, kindOf(type),
                        type.getNameAsString(), parent, typeName, Map.copyOf(typeAttributes)));
                for (var field : type.getFields()) {
                    for (var variable : field.getVariables()) {
                        var address = typeName + "#" + variable.getNameAsString();
                        var attributes = new HashMap<>(attributes(field));
                        attributes.put("type", variable.getType().asString());
                        elements.add(new Element(idOf(address, Kind.FIELD), Kind.FIELD,
                                variable.getNameAsString(), typeId, address, Map.copyOf(attributes)));
                    }
                }
                for (var constructor : type.getConstructors()) {
                    var address = methodAddress(typeName, constructor);
                    var constructorId = idOf(address, Kind.CONSTRUCTOR);
                    elements.add(new Element(constructorId, Kind.CONSTRUCTOR, type.getNameAsString(), typeId, address,
                            attributes(constructor)));
                    addParams(elements, constructor, address, constructorId);
                }
                for (var method : type.getMethods()) {
                    var address = methodAddress(typeName, method);
                    var methodId = idOf(address, Kind.METHOD);
                    var methodAttributes = new HashMap<>(attributes(method));
                    methodAttributes.put("type", method.getType().asString());
                    if (kindOf(type) == Kind.INTERFACE && method.getAccessSpecifier().asString().isEmpty()) {
                        methodAttributes.put("visibility", "public"); // interface members are implicitly public
                    }
                    elements.add(new Element(methodId, Kind.METHOD,
                            method.getNameAsString(), typeId, address, Map.copyOf(methodAttributes)));
                    addParams(elements, method, address, methodId);
                }
            }
            var uses = new ArrayList<Node>();
            uses.addAll(fieldUses(cu));
            uses.addAll(cu.findAll(MethodCallExpr.class));
            uses.addAll(cu.findAll(ClassOrInterfaceType.class));
            uses.addAll(cu.findAll(ObjectCreationExpr.class));
            uses.sort(Comparator.comparing(n -> n.getBegin().orElseThrow()));
            for (var use : uses) {
                var target = switch (use) {
                    case MethodCallExpr call -> resolveMethod(call);
                    case ObjectCreationExpr creation -> resolveConstructor(creation);
                    case ClassOrInterfaceType type -> resolveType(type);
                    default -> {
                        var field = resolveField((Expression) use);
                        yield field != null || !(use instanceof NameExpr name) ? field : resolveTypeName(name);
                    }
                };
                addReference(references, nodes, use, target);
            }
        }
        referenceNodes = nodes;
        return new ProgramGraph(elements, references);
    }

    /**
     * Records a use of a workspace element (JDK and unresolved uses are not tracked yet).
     * Its id is the site plus the ordinal of the use within the site, in source order.
     */
    private void addReference(List<Reference> references, Map<String, Node> nodes, Node use, String target) {
        if (target == null || !ids.containsKey(target)) return;
        var site = siteOf(use);
        var ordinal = references.stream().filter(r -> r.site().equals(site)).count();
        var id = site + "@" + ordinal;
        nodes.put(id, use);
        var name = use instanceof ObjectCreationExpr creation ? creation.getType().getNameAsString()
                : ((NodeWithSimpleName<?>) use).getNameAsString();
        var mode = use instanceof NameExpr && !target.contains("#") ? "type" : modeOf(use);
        references.add(new Reference(id, site, ids.get(target), name, mode));
    }

    /** A name used as a type (e.g. the receiver in {@code Money.of(1)}): its qualified name, or null. */
    private String resolveTypeName(NameExpr name) {
        if (name.getParentNode().filter(p -> p instanceof MethodCallExpr || p instanceof FieldAccessExpr).isEmpty()) return null;
        try {
            var type = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory.getContext(name, typeSolver)
                    .solveType(name.getNameAsString());
            return type.isSolved() ? type.getCorrespondingDeclaration().getQualifiedName() : null;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** Address of the workspace constructor a {@code new} resolves to, or null. */
    private static String resolveConstructor(ObjectCreationExpr creation) {
        try {
            var constructor = creation.resolve();
            var params = new ArrayList<String>();
            for (var i = 0; i < constructor.getNumberOfParams(); i++) params.add(simple(constructor.getParam(i).getType().describe()));
            return constructor.declaringType().getQualifiedName() + "#new(" + String.join(",", params) + ")";
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** Qualified name of the type a type use resolves to, or null. */
    private static String resolveType(ClassOrInterfaceType type) {
        try {
            return type.resolve().asReferenceType().getQualifiedName();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static String modeOf(Node use) {
        if (use instanceof MethodCallExpr) return "call";
        if (use instanceof ObjectCreationExpr) return "new";
        if (use instanceof ClassOrInterfaceType) return "type";
        var parent = use.getParentNode().orElseThrow();
        if (parent instanceof AssignExpr assign && assign.getTarget() == use) {
            return assign.getOperator() == AssignExpr.Operator.ASSIGN ? "write" : "update";
        }
        if (parent instanceof UnaryExpr unary && (unary.getOperator().isPrefix() || unary.getOperator().isPostfix())
                && unary.getOperator() != UnaryExpr.Operator.MINUS && unary.getOperator() != UnaryExpr.Operator.PLUS
                && unary.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
                && unary.getOperator() != UnaryExpr.Operator.BITWISE_COMPLEMENT) {
            return "update";
        }
        return "read";
    }

    /** Address of the method the call resolves to, or null if unresolvable. */
    private static String resolveMethod(MethodCallExpr call) {
        try {
            var method = call.resolve();
            var params = new ArrayList<String>();
            for (var i = 0; i < method.getNumberOfParams(); i++) params.add(simple(method.getParam(i).getType().describe()));
            return method.declaringType().getQualifiedName() + "#" + method.getName() + "(" + String.join(",", params) + ")";
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    private static List<Expression> fieldUses(CompilationUnit cu) {
        var uses = new ArrayList<Expression>();
        uses.addAll(cu.findAll(NameExpr.class));
        uses.addAll(cu.findAll(FieldAccessExpr.class));
        return uses;
    }

    /** Address of the field the expression resolves to, or null if it is not a (resolvable) field use. */
    private static String resolveField(Expression expr) {
        try {
            ResolvedValueDeclaration decl = expr.isNameExpr() ? expr.asNameExpr().resolve() : expr.asFieldAccessExpr().resolve();
            if (!decl.isField()) return null;
            return ((ResolvedFieldDeclaration) decl).declaringType().getQualifiedName() + "#" + decl.getName();
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** The innermost member (method or field) containing the node, else its type. */
    private ElementId siteOf(Node node) {
        var type = node.findAncestor(TypeDeclaration.class).orElseThrow();
        var typeName = type.getFullyQualifiedName().orElseThrow().toString();
        var method = node.findAncestor(CallableDeclaration.class);
        if (method.isPresent() && method.get().findAncestor(TypeDeclaration.class).orElse(null) == type) {
            return ids.get(methodAddress(typeName, method.get()));
        }
        var field = node.findAncestor(VariableDeclarator.class).filter(v -> v.getParentNode()
                .filter(FieldDeclaration.class::isInstance).isPresent());
        return field.map(v -> ids.get(typeName + "#" + v.getNameAsString())).orElseGet(() -> ids.get(typeName));
    }

    /**
     * {@code a.B#m(int,String)}: parameter types resolved (so declarations and calls agree) and written with simple
     * names (so moving a type does not change the address of the methods that take it).
     */
    private static String methodAddress(String typeName, CallableDeclaration<?> method) {
        var name = method instanceof ConstructorDeclaration ? "new" : method.getNameAsString();
        return typeName + "#" + name + method.getParameters().stream()
                .map(p -> simple(describe(p.getType()))).collect(Collectors.joining(",", "(", ")"));
    }

    private static final java.util.regex.Pattern PACKAGE_PREFIX = java.util.regex.Pattern.compile("\\b(?:[a-z_$][\\w$]*\\.)+");

    private static String simple(String described) {
        return PACKAGE_PREFIX.matcher(described).replaceAll("").replace(" ", "");
    }

    private static String describe(Type type) {
        try {
            return type.resolve().describe();
        } catch (RuntimeException unresolved) {
            return type.asString();
        }
    }

    private void addParams(List<Element> elements, CallableDeclaration<?> callable, String address, ElementId owner) {
        for (var param : callable.getParameters()) {
            var paramAddress = address + "/" + param.getNameAsString();
            elements.add(new Element(idOf(paramAddress, Kind.PARAM), Kind.PARAM, param.getNameAsString(),
                    owner, paramAddress, Map.of("type", param.getType().asString())));
        }
    }

    /** Classes, interfaces and enums of a unit, outermost first. */
    private static List<TypeDeclaration<?>> types(CompilationUnit cu) {
        var types = new ArrayList<TypeDeclaration<?>>();
        cu.findAll(TypeDeclaration.class).forEach(t -> {
            if (t instanceof ClassOrInterfaceDeclaration || t instanceof EnumDeclaration) types.add(t);
        });
        return types;
    }

    private static Kind kindOf(TypeDeclaration<?> type) {
        if (type instanceof EnumDeclaration) return Kind.ENUM;
        return ((ClassOrInterfaceDeclaration) type).isInterface() ? Kind.INTERFACE : Kind.CLASS;
    }

    /** visibility; superclass (classes); implements: interfaces implemented, or extended by an interface. */
    private static Map<String, String> typeAttributes(TypeDeclaration<?> type) {
        var attributes = new HashMap<>(attributes(type));
        NodeList<ClassOrInterfaceType> interfaces = new NodeList<>();
        if (type instanceof ClassOrInterfaceDeclaration c && c.isInterface()) {
            interfaces = c.getExtendedTypes();
        } else if (type instanceof ClassOrInterfaceDeclaration c) {
            if (c.getExtendedTypes().isNonEmpty()) {
                var superclass = resolveType(c.getExtendedTypes(0));
                if (superclass != null) attributes.put("superclass", superclass);
            }
            interfaces = c.getImplementedTypes();
        } else if (type instanceof EnumDeclaration e) {
            interfaces = e.getImplementedTypes();
        }
        attributes.put("implements", interfaces.stream().map(JavaProject::resolveType)
                .filter(java.util.Objects::nonNull).collect(Collectors.joining(",")));
        return Map.copyOf(attributes);
    }

    private static Map<String, String> attributes(NodeWithModifiers<?> node) {
        var visibility = node.getAccessSpecifier().asString();
        return Map.of("visibility", visibility.isEmpty() ? "package" : visibility);
    }

    // ---- identity

    private ElementId idOf(String address, Kind kind) {
        return ids.computeIfAbsent(address, a -> new ElementId(kind.prefix() + ":" + nextId++));
    }

    /** Moves the identity at {@code oldAddress}, and that of everything addressed under it, to {@code newAddress}. */
    private void transfer(String oldAddress, String newAddress) {
        if (oldAddress.equals(newAddress)) return;
        for (var address : List.copyOf(ids.keySet())) {
            if (address.equals(oldAddress)) {
                ids.put(newAddress, ids.remove(address));
            } else if (address.startsWith(oldAddress) && "#/.".indexOf(address.charAt(oldAddress.length())) >= 0) {
                ids.put(newAddress + address.substring(oldAddress.length()), ids.remove(address));
            }
        }
    }

    // ---- AST lookup and text edits

    private Node declaration(Element element) {
        if (element.kind() == Kind.PARAM) {
            return ((CallableDeclaration<?>) declaration(graph().element(element.parent())))
                    .getParameterByName(element.name()).orElseThrow();
        }
        if (element.kind().isType()) return typeDeclaration(element.qualified());
        var type = typeDeclaration(graph().element(element.parent()).qualified());
        return switch (element.kind()) {
            case FIELD -> type.getFieldByName(element.name()).orElseThrow();
            case CONSTRUCTOR -> type.getConstructors().stream()
                    .filter(c -> methodAddress(element.qualified().substring(0, element.qualified().indexOf('#')), c)
                            .equals(element.qualified()))
                    .findFirst().orElseThrow();
            case METHOD -> type.getMethods().stream()
                    .filter(m -> methodAddress(element.qualified().substring(0, element.qualified().indexOf('#')), m)
                            .equals(element.qualified()))
                    .findFirst().orElseThrow();
            default -> throw new IllegalArgumentException("not a member: " + element);
        };
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
        typeSolver.reset();
        graph = null;
        referenceNodes = Map.of();
        diagnostics = null;
    }

    private String pathOf(CompilationUnit cu) {
        return units.entrySet().stream().filter(e -> e.getValue() == cu)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

}
