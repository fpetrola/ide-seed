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
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
    private final Map<String, CompilationUnit> units = new TreeMap<>();
    private Map<String, ElementId> ids = new HashMap<>();
    private int nextId;
    private ProgramGraph graph;
    private Map<String, Node> referenceNodes = Map.of();
    private List<Diagnostic> diagnostics;

    private JavaProject() {
        var solver = new WorkspaceTypeSolver(units::values, new ReflectionTypeSolver());
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

    public Snapshot snapshot() {
        var sources = new LinkedHashMap<String, String>();
        units.keySet().forEach(path -> sources.put(path, source(path)));
        return new Snapshot(sources, Map.copyOf(ids), nextId);
    }

    public void restore(Snapshot snapshot) {
        units.clear();
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
                    : site.get().findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow().getFullyQualifiedName().orElseThrow();
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
        var owner = graph().element(parent);
        return switch (kind) {
            case CLASS -> {
                if (owner.kind() != Kind.PACKAGE) throw new IllegalArgumentException("classes go in packages: " + owner);
                var path = owner.qualified().replace('.', '/') + "/" + name + ".java";
                if (units.containsKey(path)) throw new IllegalArgumentException("already exists: " + path);
                load(path, "package " + owner.qualified() + ";\n\npublic class " + name + " {\n}\n");
                yield idOf(owner.qualified() + "." + name, Kind.CLASS);
            }
            case METHOD -> {
                insertMember(typeDeclaration(owner.qualified()), "void " + name + "() {\n" + " ".repeat(4) + "}");
                yield idOf(owner.qualified() + "#" + name + "()", Kind.METHOD);
            }
            case PARAM -> {
                var method = editMethod(owner, m -> m.addParameter("Object", name));
                yield idOf(method + "/" + name, Kind.PARAM);
            }
            default -> throw new IllegalArgumentException("unsupported create: " + kind);
        };
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
        var anchor = before == null ? null : g.element(before);
        var node = declaration(element);
        var text = LexicalPreservingPrinter.print(node).strip();
        node.remove();
        changed();
        if (anchor == null) insertMember(typeDeclaration(target.qualified()), text);
        else insertBefore(declaration(anchor), text);
        var signature = element.qualified().substring(element.qualified().indexOf('#'));
        transfer(element.qualified(), target.qualified() + signature);
    }

    /** Renames a field or method together with every use that resolves to it, keeping its identity. */
    @Override
    public void rename(ElementId id, String name) {
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
        uses.forEach(use -> use.setName(name));
        var owner = element.qualified().substring(0, element.qualified().indexOf('#') + 1);
        var rest = element.qualified().substring(owner.length() + element.name().length());
        transfer(element.qualified(), owner + name + rest);
        changed();
    }

    /** Expressions anywhere in the workspace that resolve to the field or method at {@code address}. */
    private List<NodeWithSimpleName<?>> usesOf(String address) {
        var uses = new ArrayList<NodeWithSimpleName<?>>();
        for (var cu : units.values()) {
            for (var expr : fieldUses(cu)) {
                if (address.equals(resolveField(expr))) uses.add((NodeWithSimpleName<?>) expr);
            }
            for (var call : cu.findAll(MethodCallExpr.class)) {
                if (address.equals(resolveMethod(call))) uses.add(call);
            }
        }
        return uses;
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
            default -> throw new IllegalArgumentException("unsupported attribute: " + attribute);
        }
        changed();
    }

    /** Adds an import to the unit of a top-level type: after the last import, or after the package line. */
    private void addImport(ElementId id, String qualified) {
        var cu = typeDeclaration(graph().element(id).qualified()).findCompilationUnit().orElseThrow();
        var path = pathOf(cu);
        var code = source(path);
        if (cu.getImports().isNonEmpty()) {
            var offset = lineStart(code, cu.getImports().getLast().orElseThrow().getEnd().orElseThrow().line + 1);
            load(path, code.substring(0, offset) + "import " + qualified + ";\n" + code.substring(offset));
        } else {
            var line = cu.getPackageDeclaration().map(p -> p.getEnd().orElseThrow().line).orElse(0);
            var offset = lineStart(code, line + 1);
            load(path, code.substring(0, offset) + "\nimport " + qualified + ";\n" + code.substring(offset));
        }
    }

    private void setType(ElementId id, String type) {
        var element = graph().element(id);
        var parsed = parser.parseType(type).getResult().orElseThrow();
        switch (element.kind()) {
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
        var type = typeDeclaration(graph().element(id).qualified());
        var pkg = type.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString() + ".").orElse("");
        var name = superclass.startsWith(pkg) && superclass.indexOf('.', pkg.length()) < 0
                ? superclass.substring(pkg.length()) : superclass;
        if (type.getExtendedTypes().isEmpty()) type.addExtendedType(name);
        else type.getExtendedTypes(0).setName(name);
    }

    private void setVisibility(ElementId id, String value) {
        var modifiers = ((NodeWithModifiers<?>) declaration(graph().element(id))).getModifiers();
        modifiers.removeIf(m -> ACCESS.contains(m.getKeyword()));
        if (!value.equals("package")) modifiers.addFirst(new Modifier(Modifier.Keyword.valueOf(value.toUpperCase())));
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
        var target = graph().element(targetId);
        if (target.kind() != Kind.METHOD) throw new IllegalArgumentException("can only retarget to methods: " + target);
        var scope = node.isFieldAccessExpr() ? node.asFieldAccessExpr().getScope() : null;
        switch (ref.mode()) {
            case "read" -> node.replace(new MethodCallExpr(scope, target.name()));
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
            for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                var typeName = type.getFullyQualifiedName().orElseThrow();
                var parent = type.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(t -> idOf(t.getFullyQualifiedName().orElseThrow(), Kind.CLASS)).orElse(pkg);
                var typeId = idOf(typeName, Kind.CLASS);
                var typeAttributes = new HashMap<>(typeAttributes(type));
                if (type.isTopLevelType()) typeAttributes.put("file", pathOf(cu));
                elements.add(new Element(typeId, type.isInterface() ? Kind.INTERFACE : Kind.CLASS,
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
                for (var method : type.getMethods()) {
                    var address = methodAddress(typeName, method);
                    var methodId = idOf(address, Kind.METHOD);
                    elements.add(new Element(methodId, Kind.METHOD,
                            method.getNameAsString(), typeId, address, attributes(method)));
                    for (var param : method.getParameters()) {
                        var paramAddress = address + "/" + param.getNameAsString();
                        elements.add(new Element(idOf(paramAddress, Kind.PARAM), Kind.PARAM, param.getNameAsString(),
                                methodId, paramAddress, Map.of("type", param.getType().asString())));
                    }
                }
            }
            var uses = new ArrayList<Node>();
            uses.addAll(fieldUses(cu));
            uses.addAll(cu.findAll(MethodCallExpr.class));
            uses.sort(Comparator.comparing(n -> n.getBegin().orElseThrow()));
            for (var use : uses) {
                var target = use instanceof MethodCallExpr call ? resolveMethod(call) : resolveField((Expression) use);
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
        references.add(new Reference(id, site, ids.get(target), ((NodeWithSimpleName<?>) use).getNameAsString(), modeOf(use)));
    }

    private static String modeOf(Node use) {
        if (use instanceof MethodCallExpr) return "call";
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
            for (var i = 0; i < method.getNumberOfParams(); i++) params.add(method.getParam(i).getType().describe());
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
        var type = node.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
        var typeName = type.getFullyQualifiedName().orElseThrow();
        var method = node.findAncestor(MethodDeclaration.class);
        if (method.isPresent()) return ids.get(methodAddress(typeName, method.get()));
        var field = node.findAncestor(VariableDeclarator.class).filter(v -> v.getParentNode()
                .filter(FieldDeclaration.class::isInstance).isPresent());
        return field.map(v -> ids.get(typeName + "#" + v.getNameAsString())).orElseGet(() -> ids.get(typeName));
    }

    /** {@code a.B#m(int,java.lang.String)}: parameter types resolved, so declarations and calls agree. */
    private static String methodAddress(String typeName, MethodDeclaration method) {
        return typeName + "#" + method.getNameAsString() + method.getParameters().stream()
                .map(p -> describe(p.getType())).collect(Collectors.joining(",", "(", ")"));
    }

    private static String describe(Type type) {
        try {
            return type.resolve().describe();
        } catch (RuntimeException unresolved) {
            return type.asString();
        }
    }

    private static Map<String, String> typeAttributes(ClassOrInterfaceDeclaration type) {
        var attributes = new HashMap<>(attributes(type));
        if (!type.isInterface() && type.getExtendedTypes().isNonEmpty()) {
            attributes.put("superclass", type.getExtendedTypes(0).resolve().asReferenceType().getQualifiedName());
        }
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
            return ((MethodDeclaration) declaration(graph().element(element.parent())))
                    .getParameterByName(element.name()).orElseThrow();
        }
        var type = typeDeclaration(graph().element(element.parent()).qualified());
        return switch (element.kind()) {
            case FIELD -> type.getFieldByName(element.name()).orElseThrow();
            case METHOD -> type.getMethods().stream()
                    .filter(m -> methodAddress(element.qualified().substring(0, element.qualified().indexOf('#')), m)
                            .equals(element.qualified()))
                    .findFirst().orElseThrow();
            default -> throw new IllegalArgumentException("not a member: " + element);
        };
    }

    private ClassOrInterfaceDeclaration typeDeclaration(String qualified) {
        return units.values().stream()
                .flatMap(cu -> cu.findAll(ClassOrInterfaceDeclaration.class).stream())
                .filter(t -> t.getFullyQualifiedName().orElse("").equals(qualified))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no type " + qualified));
    }

    /** Inserts a member as a text edit just before the type's closing brace, then re-parses the unit. */
    private void insertMember(ClassOrInterfaceDeclaration type, String memberText) {
        var path = pathOf(type.findCompilationUnit().orElseThrow());
        var code = source(path);
        var offset = lineStart(code, type.getEnd().orElseThrow().line);
        var indent = " ".repeat(4);
        load(path, code.substring(0, offset) + "\n" + indent + memberText + "\n" + code.substring(offset));
    }

    /** Inserts a member as a text edit on the lines just above {@code anchor}, then re-parses the unit. */
    private void insertBefore(Node anchor, String memberText) {
        var path = pathOf(anchor.findCompilationUnit().orElseThrow());
        var code = source(path);
        var offset = lineStart(code, anchor.getBegin().orElseThrow().line);
        var indent = " ".repeat(4);
        load(path, code.substring(0, offset) + indent + memberText + "\n\n" + code.substring(offset));
    }

    /** Replaces the text of a node, then re-parses its unit. */
    private void replaceText(Node node, String text) {
        var path = pathOf(node.findCompilationUnit().orElseThrow());
        var code = source(path);
        var range = node.getRange().orElseThrow();
        var start = lineStart(code, range.begin.line) + range.begin.column - 1;
        var end = lineStart(code, range.end.line) + range.end.column;
        load(path, code.substring(0, start) + text + code.substring(end));
    }

    private void load(String path, String code) {
        units.put(path, parse(code));
        invalidate();
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
        units.replaceAll((path, cu) -> parse(LexicalPreservingPrinter.print(cu)));
        invalidate();
    }

    private void invalidate() {
        JavaParserFacade.clearInstances(); // resolution caches point into replaced ASTs
        graph = null;
        referenceNodes = Map.of();
        diagnostics = null;
    }

    private String pathOf(CompilationUnit cu) {
        return units.entrySet().stream().filter(e -> e.getValue() == cu)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static int lineStart(String code, int line) {
        var offset = 0;
        for (var i = 1; i < line; i++) offset = code.indexOf('\n', offset) + 1;
        return offset;
    }
}
