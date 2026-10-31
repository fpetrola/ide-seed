package ide.java.semantics;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
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
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import ide.java.syntax.Decl;
import ide.java.text.Unit;
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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ide.java.semantics.Resolution.*;

/**
 * Reads the program from the units: elements (packages and types first, since members key their parameter types by
 * type id), then the uses that resolve to them. Identity decides who is who: a known address keeps its id.
 */
public final class Reader {

    /** What a reading gives: the program, the node behind each reference, and where each element is declared. */
    public record Reading(Program program, Map<String, Node> uses, Map<ElementId, Decl> declarations) {

        public Decl declaration(ElementId element) {
            var declaration = declarations.get(element);
            if (declaration == null) throw new IllegalArgumentException("no declaration of " + element);
            return declaration;
        }

        public Node use(String reference) {
            var use = uses.get(reference);
            if (use == null) throw new IllegalArgumentException("no use " + reference);
            return use;
        }
    }

    private final Identity ids;
    private final Keys keys;
    private final WorkspaceTypeSolver typeSolver;

    public Reader(Identity ids, Keys keys, WorkspaceTypeSolver typeSolver) {
        this.ids = ids;
        this.keys = keys;
        this.typeSolver = typeSolver;
    }

    public Reading read(Collection<Unit> units, Set<String> emptyPackages) {
        var elements = new ArrayList<Element>();
        var declarations = new HashMap<ElementId, Decl>();
        var packages = new LinkedHashMap<String, ElementId>();
        for (var unit : units) {
            unit.tree().getPackageDeclaration().ifPresent(p -> packages.computeIfAbsent(p.getNameAsString(), n -> addPackage(elements, n)));
            for (var type : types(unit.tree())) ids.of(new Address.Type(qualifiedOf(type)), Kind.CLASS);
        }
        for (var name : emptyPackages) packages.computeIfAbsent(name, n -> addPackage(elements, n));
        for (var unit : units) {
            var pkg = unit.tree().getPackageDeclaration().map(p -> packages.get(p.getNameAsString())).orElse(null);
            for (var type : types(unit.tree())) {
                var typeName = qualifiedOf(type);
                var typeId = ids.get(new Address.Type(typeName));
                var parent = type.getParentNode().filter(TypeDeclaration.class::isInstance)
                        .map(t -> ids.get(new Address.Type(qualifiedOf((TypeDeclaration<?>) t)))).orElse(pkg);
                var typeFacts = new HashMap<>(attributes(type));
                if (type.isTopLevelType()) typeFacts.put(Fact.FILE, unit.path());
                if (type instanceof ClassOrInterfaceDeclaration c && !c.isInterface() && c.getExtendedTypes().isNonEmpty()) {
                    typeFacts.put(Fact.SUPERCLASS, c.getExtendedTypes(0).asString());
                }
                elements.add(new Element(typeId, kindOf(type), type.getNameAsString(), parent, new Address.Type(typeName), typeName,
                        Map.copyOf(typeFacts)));
                declarations.put(typeId, new Decl(unit, type));
                for (var field : type.getFields()) {
                    for (var variable : field.getVariables()) {
                        var facts = new HashMap<>(attributes(field));
                        facts.put(Fact.TYPE, variable.getType().asString());
                        var id = add(elements, new Address.Field(typeId, variable.getNameAsString()), Kind.FIELD,
                                variable.getNameAsString(), typeId, facts);
                        declarations.put(id, new Decl(unit, field));
                    }
                }
                for (var constructor : type.getConstructors()) {
                    var id = add(elements, new Address.Callable(typeId, "new", keys.of(constructor)), Kind.CONSTRUCTOR,
                            type.getNameAsString(), typeId, attributes(constructor));
                    declarations.put(id, new Decl(unit, constructor));
                    addParams(elements, declarations, unit, constructor, id);
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
                    declarations.put(id, new Decl(unit, method));
                    addParams(elements, declarations, unit, method, id);
                }
            }
        }
        // references only once every element has its id: a use may point into a unit read later
        var references = new ArrayList<Reference>();
        var uses = new HashMap<String, Node>();
        var ordinals = new HashMap<ElementId, Integer>();
        for (var unit : units) {
            var cu = unit.tree();
            var nodes = new ArrayList<Node>();
            nodes.addAll(fieldUses(cu));
            nodes.addAll(cu.findAll(MethodCallExpr.class));
            nodes.addAll(cu.findAll(ClassOrInterfaceType.class));
            nodes.addAll(cu.findAll(ObjectCreationExpr.class));
            nodes.addAll(cu.findAll(MethodReferenceExpr.class));
            nodes.sort(Comparator.comparing(n -> n.getBegin().orElseThrow()));
            for (var use : nodes) {
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
                if (target == null) continue; // JDK and unresolved uses are not tracked yet
                var site = siteOf(use);
                var id = site + "@" + (ordinals.merge(site, 1, Integer::sum) - 1); // the site, and the use's place in it
                uses.put(id, use);
                var name = use instanceof ObjectCreationExpr creation ? creation.getType().getNameAsString()
                        : use instanceof MethodReferenceExpr reference ? reference.getIdentifier()
                        : ((NodeWithSimpleName<?>) use).getNameAsString();
                var mode = use instanceof NameExpr && ids.address(target) instanceof Address.Type ? Mode.TYPE : modeOf(use);
                references.add(new Reference(id, site, target, name, mode));
            }
        }
        return new Reading(new Program(elements, references), uses, declarations);
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

    private void addParams(List<Element> elements, Map<ElementId, Decl> declarations, Unit unit, CallableDeclaration<?> callable,
                           ElementId owner) {
        for (var param : callable.getParameters()) {
            var id = add(elements, new Address.Param(owner, param.getNameAsString()), Kind.PARAM, param.getNameAsString(), owner,
                    Map.of(Fact.TYPE, param.getType().asString()));
            declarations.put(id, new Decl(unit, param));
        }
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
    public String resolveTypeName(NameExpr name) {
        if (name.getParentNode().filter(p -> p instanceof MethodCallExpr || p instanceof FieldAccessExpr).isEmpty()) return null;
        try {
            var type = JavaParserFactory.getContext(name, typeSolver).solveType(name.getNameAsString());
            return type.isSolved() ? type.getCorrespondingDeclaration().getQualifiedName() : null;
        } catch (RuntimeException unresolved) {
            return null;
        }
    }

    /** The workspace method the call resolves to, or null. */
    public ElementId resolveMethod(MethodCallExpr call) {
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
    public ElementId siteOf(Node node) {
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

    public static String qualifiedOf(TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElseThrow();
    }
}
