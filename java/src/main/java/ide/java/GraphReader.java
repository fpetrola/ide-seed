package ide.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import ide.program.Address;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Identity;
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
    record Read(Program program, Map<String, Node> nodes) {
    }

    private final Identity ids;
    private final Keys keys;
    private final WorkspaceTypeSolver typeSolver;

    GraphReader(Identity ids, Keys keys, WorkspaceTypeSolver typeSolver) {
        this.ids = ids;
        this.keys = keys;
        this.typeSolver = typeSolver;
    }

    /** The graph of {@code units} (path → unit), plus the node behind each reference id. */
    Read read(Map<String, CompilationUnit> units, Set<String> emptyPackages) {
        var elements = new ArrayList<Element>();
        var references = new ArrayList<Reference>();
        var nodes = new HashMap<String, Node>();
        var packages = new LinkedHashMap<String, ElementId>();
        // packages and types first: member addresses key their parameter types by id
        for (var cu : units.values()) {
            cu.getPackageDeclaration().ifPresent(p -> packages.computeIfAbsent(p.getNameAsString(), n -> addPackage(elements, n)));
            for (var type : types(cu)) ids.of(new Address.Type(qualifiedOf(type)), Kind.CLASS);
        }
        for (var name : emptyPackages) packages.computeIfAbsent(name, n -> addPackage(elements, n));
        for (var unit : units.entrySet()) {
            var cu = unit.getValue();
            var pkg = cu.getPackageDeclaration().map(p -> packages.get(p.getNameAsString())).orElse(null);
            for (var type : types(cu)) {
                var typeName = qualifiedOf(type);
                var typeId = ids.get(new Address.Type(typeName));
                var parent = type.getParentNode().filter(TypeDeclaration.class::isInstance)
                        .map(t -> ids.get(new Address.Type(qualifiedOf((TypeDeclaration<?>) t)))).orElse(pkg);
                var typeFacts = new HashMap<>(attributes(type));
                if (type.isTopLevelType()) typeFacts.put(Fact.FILE, unit.getKey());
                elements.add(new Element(typeId, kindOf(type), type.getNameAsString(), parent, new Address.Type(typeName), typeName,
                        Map.copyOf(typeFacts)));
                for (var field : type.getFields()) {
                    for (var variable : field.getVariables()) {
                        var facts = new HashMap<>(attributes(field));
                        facts.put(Fact.TYPE, variable.getType().asString());
                        add(elements, new Address.Field(typeId, variable.getNameAsString()), Kind.FIELD, variable.getNameAsString(), typeId, facts);
                    }
                }
                for (var constructor : type.getConstructors()) {
                    var id = add(elements, new Address.Callable(typeId, "new", keys.of(constructor)), Kind.CONSTRUCTOR,
                            type.getNameAsString(), typeId, attributes(constructor));
                    addParams(elements, constructor, id);
                }
                for (var method : type.getMethods()) {
                    var facts = new HashMap<>(attributes(method));
                    facts.put(Fact.TYPE, method.getType().asString());
                    method.getBody().ifPresent(b -> facts.put(Fact.BODY, b.toString().replaceAll("\\s+", " ")));
                    if (kindOf(type) == Kind.INTERFACE && method.getAccessSpecifier().asString().isEmpty()) {
                        facts.put(Fact.VISIBILITY, "public"); // interface members are implicitly public
                    }
                    var id = add(elements, new Address.Callable(typeId, method.getNameAsString(), keys.of(method)), Kind.METHOD,
                            method.getNameAsString(), typeId, facts);
                    addParams(elements, method, id);
                }
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
                    case ObjectCreationExpr creation -> callable(resolveConstructor(creation));
                    case MethodReferenceExpr reference -> callable(resolveMethodReference(reference));
                    case ClassOrInterfaceType type -> type(resolveType(type));
                    default -> {
                        var field = resolveField((Expression) use);
                        yield field != null ? field(field) : use instanceof NameExpr name ? type(resolveTypeName(name)) : null;
                    }
                };
                addReference(references, nodes, use, target);
            }
        }
        return new Read(new Program(elements, references), nodes);
    }

    private ElementId addPackage(List<Element> elements, String name) {
        var address = new Address.Package(name);
        var id = ids.of(address, Kind.PACKAGE);
        elements.add(new Element(id, Kind.PACKAGE, name, null, address, name, Map.of()));
        return id;
    }

    private ElementId add(List<Element> elements, Address address, Kind kind, String name, ElementId parent, Map<Fact, String> facts) {
        var id = ids.of(address, kind);
        elements.add(new Element(id, kind, name, parent, address, ids.render(address), Map.copyOf(facts)));
        return id;
    }

    private void addParams(List<Element> elements, CallableDeclaration<?> callable, ElementId owner) {
        for (var param : callable.getParameters()) {
            add(elements, new Address.Param(owner, param.getNameAsString()), Kind.PARAM, param.getNameAsString(), owner,
                    Map.of(Fact.TYPE, param.getType().asString()));
        }
    }

    /**
     * Records a use of a workspace element (JDK and unresolved uses are not tracked yet).
     * Its id is the site plus the ordinal of the use within the site, in source order.
     */
    private void addReference(List<Reference> references, Map<String, Node> nodes, Node use, ElementId target) {
        if (target == null) return;
        var site = siteOf(use);
        var ordinal = references.stream().filter(r -> r.site().equals(site)).count();
        var id = site + "@" + ordinal;
        nodes.put(id, use);
        var name = use instanceof ObjectCreationExpr creation ? creation.getType().getNameAsString()
                : use instanceof MethodReferenceExpr reference ? reference.getIdentifier()
                : ((NodeWithSimpleName<?>) use).getNameAsString();
        var mode = use instanceof NameExpr && ids.address(target) instanceof Address.Type ? Mode.TYPE : modeOf(use);
        references.add(new Reference(id, site, target, name, mode));
    }

    // ---- from resolved declarations to ids (null when not in the workspace)

    private ElementId type(String qualified) {
        return qualified == null ? null : ids.get(new Address.Type(qualified));
    }

    private ElementId callable(ResolvedMethodLikeDeclaration callable) {
        if (callable == null) return null;
        var owner = type(callable.declaringType().getQualifiedName());
        if (owner == null) return null;
        var name = callable instanceof ResolvedConstructorDeclaration ? "new" : callable.getName();
        return ids.get(new Address.Callable(owner, name, keys.of(callable)));
    }

    private ElementId field(ResolvedFieldDeclaration field) {
        var owner = type(field.declaringType().getQualifiedName());
        return owner == null ? null : ids.get(new Address.Field(owner, field.getName()));
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

    /** The workspace method the call resolves to, or null. */
    ElementId resolveMethod(MethodCallExpr call) {
        var resolved = callable(resolveMethodStrictly(call));
        return resolved != null ? resolved : resolveThroughBounds(call);
    }

    /**
     * For a receiver typed by a type variable ({@code T extends Item}), which the solver does not resolve: the one
     * workspace method of that name and arity in the variable's bounds.
     */
    private ElementId resolveThroughBounds(MethodCallExpr call) {
        try {
            if (call.getScope().isEmpty()) return null;
            var type = call.getScope().get().calculateResolvedType();
            if (!type.isTypeVariable()) return null;
            var candidates = new ArrayList<ElementId>();
            for (var bound : type.asTypeParameter().getBounds()) {
                var owner = type(bound.getType().asReferenceType().getQualifiedName());
                if (owner == null) continue;
                for (var address : ids.addresses()) {
                    if (address instanceof Address.Callable c && c.owner().equals(owner) && c.name().equals(call.getNameAsString())
                            && c.keys().size() == call.getArguments().size()) {
                        candidates.add(ids.get(address));
                    }
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
        var owner = ids.get(new Address.Type(qualifiedOf(type)));
        var method = node.findAncestor(CallableDeclaration.class);
        if (method.isPresent() && method.get().findAncestor(TypeDeclaration.class).orElse(null) == type) {
            var callable = ids.get(new Address.Callable(owner, nameOf(method.get()), keys.of(method.get())));
            var param = node.findAncestor(Parameter.class).filter(p -> p.getParentNode().orElse(null) == method.get());
            return param.map(p -> ids.get(new Address.Param(callable, p.getNameAsString()))).orElse(callable);
        }
        var field = node.findAncestor(VariableDeclarator.class).filter(v -> v.getParentNode()
                .filter(FieldDeclaration.class::isInstance).isPresent());
        return field.map(v -> ids.get(new Address.Field(owner, v.getNameAsString()))).orElse(owner);
    }

    static String nameOf(CallableDeclaration<?> callable) {
        return callable instanceof ConstructorDeclaration ? "new" : callable.getNameAsString();
    }

    static String qualifiedOf(TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElseThrow();
    }
}
