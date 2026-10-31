package ide.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import ide.graph.Element;
import ide.graph.ElementId;
import ide.graph.Kind;
import ide.graph.ProgramGraph;
import ide.graph.Reference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Java sources in memory, edited with lexical preservation, projected to a {@link ProgramGraph}.
 * Identity is kept in {@code ids} (address → id): parsing never decides identity, primitives transfer it.
 */
public final class JavaProject {

    /** Everything needed to roll back: source text and identity. */
    public record Snapshot(Map<String, String> sources, Map<String, ElementId> ids, int nextId) {
    }

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

    public ProgramGraph graph() {
        if (graph == null) graph = buildGraph();
        return graph;
    }

    // ---- primitives

    /** Moves a member into another type, keeping its identity. */
    public void move(ElementId member, ElementId newParent) {
        var g = graph();
        var element = g.element(member);
        var target = g.element(newParent);
        var node = declaration(element);
        var text = LexicalPreservingPrinter.print(node).strip();
        node.remove();
        insertMember(typeDeclaration(target.qualified()), text);
        transfer(element.qualified(), target.qualified() + "#" + element.name());
    }

    // ---- projection: graph from AST

    private ProgramGraph buildGraph() {
        var elements = new ArrayList<Element>();
        var references = new ArrayList<Reference>();
        for (var cu : units.values()) {
            for (var type : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                var typeName = type.getFullyQualifiedName().orElseThrow();
                var parent = type.findAncestor(ClassOrInterfaceDeclaration.class)
                        .map(t -> idOf(t.getFullyQualifiedName().orElseThrow(), Kind.CLASS)).orElse(null);
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
            for (var expr : fieldUses(cu)) {
                var target = resolveField(expr);
                if (target != null) references.add(new Reference(siteOf(expr), ids.get(target)));
            }
        }
        return new ProgramGraph(elements, references);
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

    private ElementId siteOf(Node node) {
        var method = node.findAncestor(MethodDeclaration.class);
        var type = node.findAncestor(ClassOrInterfaceDeclaration.class).orElseThrow();
        var typeName = type.getFullyQualifiedName().orElseThrow();
        return method.map(m -> ids.get(methodAddress(typeName, m))).orElseGet(() -> ids.get(typeName));
    }

    private static String methodAddress(String typeName, MethodDeclaration method) {
        return typeName + "#" + method.getNameAsString() + method.getParameters().stream()
                .map(p -> p.getType().asString()).collect(Collectors.joining(",", "(", ")"));
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

    private void load(String path, String code) {
        units.put(path, LexicalPreservingPrinter.setup(parser.parse(code).getResult().orElseThrow()));
        JavaParserFacade.clearInstances(); // resolution caches point into replaced ASTs
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
