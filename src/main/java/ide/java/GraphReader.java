package ide.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Program;
import ide.program.Reference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ide.java.Resolution.*;

/** Reads the program: builds the graph from the units, resolving uses with the workspace's type solver. */
final class GraphReader {

    /** A graph and, for each of its reference ids, the node of the use. */
    record Read(Program graph, Map<String, Node> nodes) {
    }

    private final Identity ids;
    private final WorkspaceTypeSolver typeSolver;

    GraphReader(Identity ids, WorkspaceTypeSolver typeSolver) {
        this.ids = ids;
        this.typeSolver = typeSolver;
    }

    /** The graph of {@code units} (path → unit), plus the node behind each reference id. */
    Read read(Map<String, CompilationUnit> units, java.util.Set<String> emptyPackages) {
        var elements = new ArrayList<Element>();
        var references = new ArrayList<Reference>();
        var nodes = new HashMap<String, Node>();
        var packages = new LinkedHashMap<String, ElementId>();
        for (var unit : units.entrySet()) {
            var cu = unit.getValue();
            var pkg = cu.getPackageDeclaration().map(p -> {
                var name = p.getNameAsString();
                return packages.computeIfAbsent(name, n -> {
                    var id = ids.of(n, Kind.PACKAGE);
                    elements.add(new Element(id, Kind.PACKAGE, n, null, n, Map.of()));
                    return id;
                });
            }).orElse(null);
            for (TypeDeclaration<?> type : types(cu)) {
                var typeName = type.getFullyQualifiedName().orElseThrow();
                var parent = type.getParentNode().filter(TypeDeclaration.class::isInstance)
                        .map(t -> ids.of(((TypeDeclaration<?>) t).getFullyQualifiedName().orElseThrow(), Kind.CLASS)).orElse(pkg);
                var typeId = ids.of(typeName, Kind.CLASS);
                var typeAttributes = new HashMap<>(attributes(type));
                if (type.isTopLevelType()) typeAttributes.put(Fact.FILE, unit.getKey());
                elements.add(new Element(typeId, kindOf(type),
                        type.getNameAsString(), parent, typeName, Map.copyOf(typeAttributes)));
                for (var field : type.getFields()) {
                    for (var variable : field.getVariables()) {
                        var address = typeName + "#" + variable.getNameAsString();
                        var attributes = new HashMap<>(attributes(field));
                        attributes.put(Fact.TYPE, variable.getType().asString());
                        elements.add(new Element(ids.of(address, Kind.FIELD), Kind.FIELD,
                                variable.getNameAsString(), typeId, address, Map.copyOf(attributes)));
                    }
                }
                for (var constructor : type.getConstructors()) {
                    var address = methodAddress(typeName, constructor);
                    var constructorId = ids.of(address, Kind.CONSTRUCTOR);
                    elements.add(new Element(constructorId, Kind.CONSTRUCTOR, type.getNameAsString(), typeId, address,
                            attributes(constructor)));
                    addParams(elements, constructor, address, constructorId);
                }
                for (var method : type.getMethods()) {
                    var address = methodAddress(typeName, method);
                    var methodId = ids.of(address, Kind.METHOD);
                    var methodAttributes = new HashMap<>(attributes(method));
                    methodAttributes.put(Fact.TYPE, method.getType().asString());
                    method.getBody().ifPresent(b -> methodAttributes.put(Fact.BODY, b.toString().replaceAll("\\s+", " ")));
                    if (kindOf(type) == Kind.INTERFACE && method.getAccessSpecifier().asString().isEmpty()) {
                        methodAttributes.put(Fact.VISIBILITY, "public"); // interface members are implicitly public
                    }
                    elements.add(new Element(methodId, Kind.METHOD,
                            method.getNameAsString(), typeId, address, Map.copyOf(methodAttributes)));
                    addParams(elements, method, address, methodId);
                }
            }
        }
        for (var name : emptyPackages) {
            if (!packages.containsKey(name)) {
                var id = ids.of(name, Kind.PACKAGE);
                packages.put(name, id);
                elements.add(new Element(id, Kind.PACKAGE, name, null, name, Map.of()));
            }
        }
        // references only once every element has its id: a use may point into a unit processed later
        for (var cu : units.values()) {
            var uses = new ArrayList<Node>();
            uses.addAll(fieldUses(cu));
            uses.addAll(cu.findAll(MethodCallExpr.class));
            uses.addAll(cu.findAll(ClassOrInterfaceType.class));
            uses.addAll(cu.findAll(ObjectCreationExpr.class));
            uses.addAll(cu.findAll(MethodReferenceExpr.class));
            uses.sort(Comparator.comparing(n -> n.getBegin().orElseThrow()));
            for (var use : uses) {
                var target = switch (use) {
                    case MethodCallExpr call -> resolveMethod(call);
                    case ObjectCreationExpr creation -> resolveConstructor(creation);
                    case MethodReferenceExpr reference -> resolveMethodReference(reference);
                    case ClassOrInterfaceType type -> resolveType(type);
                    default -> {
                        var field = resolveField((Expression) use);
                        yield field != null || !(use instanceof NameExpr name) ? field : resolveTypeName(name);
                    }
                };
                addReference(references, nodes, use, target);
            }
        }
        return new Read(new Program(elements, references), nodes);
    }

    /**
     * Records a use of a workspace element (JDK and unresolved uses are not tracked yet).
     * Its id is the site plus the ordinal of the use within the site, in source order.
     */
    private void addReference(List<Reference> references, Map<String, Node> nodes, Node use, String target) {
        if (target == null || !ids.has(target)) return;
        var site = siteOf(use);
        var ordinal = references.stream().filter(r -> r.site().equals(site)).count();
        var id = site + "@" + ordinal;
        nodes.put(id, use);
        var name = use instanceof ObjectCreationExpr creation ? creation.getType().getNameAsString()
                : use instanceof MethodReferenceExpr reference ? reference.getIdentifier()
                : ((NodeWithSimpleName<?>) use).getNameAsString();
        var mode = use instanceof NameExpr && !target.contains("#") ? Mode.TYPE : modeOf(use);
        references.add(new Reference(id, site, ids.get(target), name, mode));
    }

    /** A name used as a type (e.g. the receiver in {@code Money.of(1)}): its qualified name, or null. */
    String resolveTypeName(NameExpr name) {
        if (name.getParentNode().filter(p -> p instanceof MethodCallExpr || p instanceof FieldAccessExpr).isEmpty()) return null;
        try {
            var type = com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory.getContext(name, typeSolver)
                    .solveType(name.getNameAsString());
            return type.isSolved() ? type.getCorrespondingDeclaration().getQualifiedName() : null;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** Address of the method the call resolves to, or null if unresolvable. */
    String resolveMethod(MethodCallExpr call) {
        var resolved = resolveMethodStrictly(call);
        return resolved != null ? resolved : resolveThroughBounds(call);
    }

    /**
     * For a receiver typed by a type variable ({@code T extends Item}), which the solver does not resolve: the one
     * workspace method of that name and arity in the variable's bounds.
     */
    private String resolveThroughBounds(MethodCallExpr call) {
        try {
            if (call.getScope().isEmpty()) return null;
            var type = call.getScope().get().calculateResolvedType();
            if (!type.isTypeVariable()) return null;
            var candidates = new ArrayList<String>();
            for (var bound : type.asTypeParameter().getBounds()) {
                var owner = bound.getType().asReferenceType().getQualifiedName();
                var prefix = owner + "#" + call.getNameAsString() + "(";
                for (var address : ids.addresses()) {
                    if (!address.startsWith(prefix) || address.contains("/")) continue;
                    var params = address.substring(prefix.length(), address.length() - 1);
                    var arity = params.isEmpty() ? 0 : params.split(",").length;
                    if (arity == call.getArguments().size()) candidates.add(address);
                }
            }
            return candidates.size() == 1 ? candidates.getFirst() : null;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** The innermost element containing the node: a parameter (its declared type), a method or field, else its type. */
    ElementId siteOf(Node node) {
        var type = node.findAncestor(TypeDeclaration.class).orElseThrow();
        var typeName = type.getFullyQualifiedName().orElseThrow().toString();
        var method = node.findAncestor(CallableDeclaration.class);
        if (method.isPresent() && method.get().findAncestor(TypeDeclaration.class).orElse(null) == type) {
            var address = methodAddress(typeName, method.get());
            var param = node.findAncestor(Parameter.class).filter(p -> p.getParentNode().orElse(null) == method.get());
            return param.map(p -> ids.get(address + "/" + p.getNameAsString())).orElseGet(() -> ids.get(address));
        }
        var field = node.findAncestor(VariableDeclarator.class).filter(v -> v.getParentNode()
                .filter(FieldDeclaration.class::isInstance).isPresent());
        return field.map(v -> ids.get(typeName + "#" + v.getNameAsString())).orElseGet(() -> ids.get(typeName));
    }

    private void addParams(List<Element> elements, CallableDeclaration<?> callable, String address, ElementId owner) {
        for (var param : callable.getParameters()) {
            var paramAddress = address + "/" + param.getNameAsString();
            var attributes = new HashMap<Fact, String>();
            attributes.put(Fact.TYPE, param.getType().asString());
            elements.add(new Element(ids.of(paramAddress, Kind.PARAM), Kind.PARAM, param.getNameAsString(),
                    owner, paramAddress, Map.copyOf(attributes)));
        }
    }
}
