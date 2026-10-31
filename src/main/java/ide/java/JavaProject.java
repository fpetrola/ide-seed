package ide.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
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
import ide.graph.Element;
import ide.graph.ElementId;
import ide.graph.Kind;
import ide.graph.Program;
import ide.graph.ProgramGraph;
import ide.graph.Reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        graph = null;
    }

    @Override
    public ProgramGraph graph() {
        if (graph == null) graph = buildGraph();
        return graph;
    }

    // ---- primitives

    /** Creates a top-level class in its own compilation unit (only kind supported so far). */
    @Override
    public ElementId create(Kind kind, ElementId parent, String name) {
        var pkg = graph().element(parent);
        if (kind != Kind.CLASS || pkg.kind() != Kind.PACKAGE) {
            throw new IllegalArgumentException("unsupported create: " + kind + " in " + pkg);
        }
        var path = pkg.qualified().replace('.', '/') + "/" + name + ".java";
        if (units.containsKey(path)) throw new IllegalArgumentException("already exists: " + path);
        load(path, "package " + pkg.qualified() + ";\n\npublic class " + name + " {\n}\n");
        return idOf(pkg.qualified() + "." + name, Kind.CLASS);
    }

    /** Moves a member into another type, keeping its identity. */
    @Override
    public void move(ElementId member, ElementId newParent, ElementId before) {
        var g = graph();
        var element = g.element(member);
        var target = g.element(newParent);
        var anchor = before == null ? null : declaration(g.element(before));
        var node = declaration(element);
        var text = LexicalPreservingPrinter.print(node).strip();
        node.remove();
        if (anchor == null) insertMember(typeDeclaration(target.qualified()), text);
        else insertBefore(anchor, text);
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
            default -> throw new IllegalArgumentException("unsupported attribute: " + attribute);
        }
        changed();
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

    // ---- projection: graph from AST

    private ProgramGraph buildGraph() {
        var elements = new ArrayList<Element>();
        var references = new ArrayList<Reference>();
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
                elements.add(new Element(typeId, type.isInterface() ? Kind.INTERFACE : Kind.CLASS,
                        type.getNameAsString(), parent, typeName, typeAttributes(type)));
                for (var field : type.getFields()) {
                    for (var variable : field.getVariables()) {
                        var address = typeName + "#" + variable.getNameAsString();
                        elements.add(new Element(idOf(address, Kind.FIELD), Kind.FIELD,
                                variable.getNameAsString(), typeId, address, attributes(field)));
                    }
                }
                for (var method : type.getMethods()) {
                    var address = methodAddress(typeName, method);
                    elements.add(new Element(idOf(address, Kind.METHOD), Kind.METHOD,
                            method.getNameAsString(), typeId, address, attributes(method)));
                }
            }
            for (var expr : fieldUses(cu)) addReference(references, expr, resolveField(expr));
            for (var call : cu.findAll(MethodCallExpr.class)) addReference(references, call, resolveMethod(call));
        }
        return new ProgramGraph(elements, references);
    }

    /** Records a use of a workspace element; uses of JDK elements and unresolved ones are not tracked yet. */
    private void addReference(List<Reference> references, Node site, String target) {
        if (target == null || !ids.containsKey(target)) return;
        var name = ((NodeWithSimpleName<?>) site).getNameAsString();
        references.add(new Reference(siteOf(site), ids.get(target), name));
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

    private void transfer(String oldAddress, String newAddress) {
        ids.put(newAddress, ids.remove(oldAddress));
    }

    // ---- AST lookup and text edits

    private BodyDeclaration<?> declaration(Element element) {
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
    private void insertBefore(BodyDeclaration<?> anchor, String memberText) {
        var path = pathOf(anchor.findCompilationUnit().orElseThrow());
        var code = source(path);
        var offset = lineStart(code, anchor.getBegin().orElseThrow().line);
        var indent = " ".repeat(4);
        load(path, code.substring(0, offset) + indent + memberText + "\n\n" + code.substring(offset));
    }

    private void load(String path, String code) {
        units.put(path, LexicalPreservingPrinter.setup(parser.parse(code).getResult().orElseThrow()));
        changed();
    }

    private void changed() {
        JavaParserFacade.clearInstances(); // resolution caches point into replaced or edited ASTs
        graph = null;
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
