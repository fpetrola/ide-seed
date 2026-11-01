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
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import ide.java.syntax.Decl;
import ide.java.syntax.Shape;
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
import ide.program.Unresolved;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static ide.java.semantics.Resolution.*;

/**
 * Reads the program from the units: elements (packages and types first, since members key their parameter types by
 * type id), then the uses that resolve to them. Identity decides who is who: a known address keeps its id. A type of
 * a library that the workspace names joins the program too, with its package: its identity is its name, since nothing
 * can change it, and it stays out of the identity table, where it would change the keys of members.
 * <p>
 * A reading after a change keeps what the change cannot have affected. A file changed is read anew; the uses of
 * another file are resolved again only when they can bind differently (see {@link #affected}), so that a change to
 * one member costs the files that use it, not the program.
 */
public final class Reader {

    /**
     * What a reading gives: the program, the node behind each reference, where each element is declared, and the files
     * whose uses this reading resolved.
     */
    public record Reading(Program program, Map<String, Node> uses, Map<ElementId, Decl> declarations, Set<String> resolved) {

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

    /** What one file declares: its shape, its elements, and where each is declared. */
    private record Declared(Shape shape, List<Element> elements, Map<ElementId, Decl> declarations) {
    }

    /**
     * What the uses of one file bind: references, uses that bind nothing, their nodes, the library types named, and
     * the names of the uses the program does not follow (members of libraries, locals, calls that do not compile).
     */
    private record Bound(List<Reference> references, List<Unresolved> unresolved, Map<String, Node> nodes, List<String> library,
                         Set<String> untracked) {
    }

    /** What the last reading knew of a file, to keep what a change cannot have affected. */
    private record Part(Unit unit, Declared declared, Bound bound) {
    }

    private final Identity ids;
    private final Keys keys;
    private final WorkspaceTypeSolver typeSolver;
    private final Map<String, Element> library = new HashMap<>(); // the libraries' packages and types, by name
    private final Map<String, String> packageOf = new HashMap<>(); // a library type's package
    private Map<String, Part> parts = Map.of();
    private Program last;

    public Reader(Identity ids, Keys keys, WorkspaceTypeSolver typeSolver) {
        this.ids = ids;
        this.keys = keys;
        this.typeSolver = typeSolver;
    }

    /**
     * Reads the program again after the files at {@code changed} changed (null: every file, as when identity was
     * restored). A file changed is read anew. The uses of a file are resolved again when they can bind differently:
     * the file changed, or it names a member whose declaration changed; every file's, when a type changed.
     */
    public Reading read(Collection<Unit> units, Set<String> emptyPackages, Set<String> changed) {
        var previous = changed == null ? Map.<String, Part>of() : parts;
        var fresh = new TreeSet<String>();
        for (var unit : units) {
            var part = previous.get(unit.path());
            if (part == null || part.unit() != unit || changed.contains(unit.path())) fresh.add(unit.path());
        }
        for (var unit : units) { // types first: members key their parameter types by type id
            if (fresh.contains(unit.path())) for (var type : types(unit.tree())) ids.of(new Address.Type(qualifiedOf(type)), Kind.CLASS);
        }
        var elements = new ArrayList<Element>();
        var packages = new LinkedHashMap<String, ElementId>();
        for (var unit : units) {
            unit.tree().getPackageDeclaration().ifPresent(p -> packages.computeIfAbsent(p.getNameAsString(), n -> addPackage(elements, n)));
        }
        for (var name : emptyPackages) packages.computeIfAbsent(name, n -> addPackage(elements, n));
        var declared = new LinkedHashMap<String, Declared>();
        for (var unit : units) {
            declared.put(unit.path(), fresh.contains(unit.path()) ? declare(unit, packages) : previous.get(unit.path()).declared());
        }
        // uses only once every element has its id: a use may point into a file read later
        var resolve = affected(units, fresh, previous, declared);
        var bound = new LinkedHashMap<String, Bound>();
        for (var unit : units) bound.put(unit.path(), resolve.contains(unit.path()) ? bind(unit) : previous.get(unit.path()).bound());
        var declarations = new HashMap<ElementId, Decl>();
        declared.values().forEach(d -> {
            elements.addAll(d.elements());
            declarations.putAll(d.declarations());
        });
        var references = new ArrayList<Reference>();
        var unresolved = new ArrayList<Unresolved>();
        var nodes = new HashMap<String, Node>();
        var named = new LinkedHashMap<String, Element>(); // the library types named, with their packages, in order
        for (var b : bound.values()) {
            references.addAll(b.references());
            unresolved.addAll(b.unresolved());
            nodes.putAll(b.nodes());
            b.library().forEach(qualified -> name(qualified, named));
        }
        for (var use : unresolved) { // what a written type that binds nothing may mean joins the program too
            if (!use.mode().isTypeUse()) continue;
            for (var type : JdkTypes.named(use.name())) {
                name(library(type.getName(), type.getPackageName(), type.getSimpleName(), kindOf(type)), named);
            }
        }
        elements.addAll(named.values());
        var next = new HashMap<String, Part>();
        for (var unit : units) next.put(unit.path(), new Part(unit, declared.get(unit.path()), bound.get(unit.path())));
        parts = next;
        last = new Program(elements, references, unresolved);
        return new Reading(last, nodes, declarations, Collections.unmodifiableSet(new TreeSet<>(resolve)));
    }

    /**
     * The files whose uses may bind differently now: every file, when a type was added, removed or changed; else the
     * files read anew, and the files with a use named like a member whose declaration changed that either bound a
     * member of that member's type, of a supertype or of a subtype (where the change can capture it, lose it or
     * outdo it as an overload), or is made inside that type or a subtype (where an inherited member is looked up
     * first), or bound nothing, or bound what the program does not follow.
     */
    private Set<String> affected(Collection<Unit> units, Set<String> fresh, Map<String, Part> previous, Map<String, Declared> declared) {
        var all = new TreeSet<String>();
        units.forEach(unit -> all.add(unit.path()));
        if (previous.isEmpty() || last == null) return all;
        var changed = new HashSet<Shape.Member>();
        var paths = new TreeSet<>(fresh);
        paths.addAll(previous.keySet().stream().filter(path -> !declared.containsKey(path)).toList()); // files removed
        for (var path : paths) {
            var before = previous.containsKey(path) ? previous.get(path).declared().shape() : Shape.NONE;
            var after = declared.containsKey(path) ? declared.get(path).shape() : Shape.NONE;
            var members = before.changedMembers(after);
            if (members == null) return all;
            changed.addAll(members);
        }
        var names = new HashSet<String>();
        var owners = new HashSet<ElementId>(); // the types whose members changed, their supertypes and their subtypes
        var below = new HashSet<ElementId>(); // the types whose members changed, and their subtypes
        for (var member : changed) {
            names.add(member.name());
            last.find(member.owner()).ifPresent(type -> {
                owners.add(type.id());
                below.add(type.id());
                last.ancestors(type).forEach(ancestor -> owners.add(ancestor.id()));
                for (var pending = new ArrayDeque<>(last.subtypes(type)); !pending.isEmpty(); ) {
                    var subtype = pending.poll();
                    if (below.add(subtype.id())) pending.addAll(last.subtypes(subtype));
                    owners.add(subtype.id());
                }
            });
        }
        var affected = new TreeSet<>(fresh);
        for (var unit : units) {
            if (affected.contains(unit.path())) continue;
            var bound = previous.get(unit.path()).bound();
            var touched = !Collections.disjoint(bound.untracked(), names)
                    || bound.unresolved().stream().anyMatch(use -> names.contains(use.name()))
                    || bound.references().stream().anyMatch(ref -> names.contains(ref.name())
                    && (owners.contains(ownerOf(ref.target())) || inside(ref.site(), below)));
            if (touched) affected.add(unit.path());
        }
        return affected;
    }

    private ElementId ownerOf(ElementId element) {
        return last.contains(element) ? last.element(element).parent() : null;
    }

    /** Whether {@code site} is inside one of {@code types}, or inside a type nested in one of them. */
    private boolean inside(ElementId site, Set<ElementId> types) {
        for (var e = last.contains(site) ? last.element(site) : null; e != null; e = e.parent() == null ? null : last.element(e.parent())) {
            if (types.contains(e.id())) return true;
        }
        return false;
    }

    /** The elements one file declares, under the packages read. */
    private Declared declare(Unit unit, Map<String, ElementId> packages) {
        var elements = new ArrayList<Element>();
        var declarations = new HashMap<ElementId, Decl>();
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
        return new Declared(Shape.of(unit), elements, declarations);
    }

    /** What the uses of one file bind; a use's id is its site and its place among the site's uses. */
    private Bound bind(Unit unit) {
        var references = new ArrayList<Reference>();
        var unresolved = new ArrayList<Unresolved>();
        var nodes = new HashMap<String, Node>();
        var named = new LinkedHashSet<String>();
        var untracked = new HashSet<String>();
        var ordinals = new HashMap<ElementId, Integer>();
        var cu = unit.tree();
        var uses = new ArrayList<Node>();
        uses.addAll(fieldUses(cu));
        uses.addAll(cu.findAll(MethodCallExpr.class));
        uses.addAll(cu.findAll(ClassOrInterfaceType.class));
        uses.addAll(cu.findAll(ObjectCreationExpr.class));
        uses.addAll(cu.findAll(MethodReferenceExpr.class));
        uses.sort(Comparator.comparing(n -> n.getBegin().orElseThrow()));
        for (var use : uses) {
            var typeName = false;
            var bindsNothing = false;
            ElementId target;
            switch (use) {
                case MethodCallExpr call -> {
                    target = resolveMethod(call);
                    bindsNothing = target == null && callBindsNothing(call);
                }
                case ObjectCreationExpr creation -> target = callable(resolveConstructor(creation));
                case MethodReferenceExpr reference -> target = callable(resolveMethodReference(reference));
                case ClassOrInterfaceType type -> {
                    var declaration = resolveDeclaration(type);
                    target = named(declaration, named);
                    bindsNothing = declaration == null && bindsNothing(type);
                }
                default -> {
                    var field = resolveField((Expression) use);
                    var type = field == null && use instanceof NameExpr name ? resolveTypeDeclaration(name) : null;
                    typeName = type != null;
                    target = field != null ? field(field) : named(type, named);
                    bindsNothing = field == null && type == null && valueBindsNothing((Expression) use);
                }
            }
            var name = use instanceof ObjectCreationExpr creation ? creation.getType().getNameAsString()
                    : use instanceof MethodReferenceExpr reference ? reference.getIdentifier()
                    : ((NodeWithSimpleName<?>) use).getNameAsString();
            var site = target == null && !bindsNothing ? null : siteOf(use);
            if (site == null) { // members of libraries, locals, what does not compile, in declarations it cannot read
                untracked.add(name);
                continue;
            }
            var id = site + "@" + (ordinals.merge(site, 1, Integer::sum) - 1);
            nodes.put(id, use);
            var mode = typeName ? Mode.TYPE : modeOf(use);
            if (bindsNothing && use instanceof ClassOrInterfaceType) unresolved.add(new Unresolved(id, site, name, mode));
            else if (bindsNothing) unresolved.add(new Unresolved(id, site, name, mode, scopeOf(use), Needs.expected((Expression) use),
                    use instanceof MethodCallExpr call ? Needs.arguments(call) : List.of()));
            else references.add(new Reference(id, site, target, name, mode));
        }
        return new Bound(references, unresolved, nodes, List.copyOf(named), Set.copyOf(untracked));
    }

    /** The workspace type a member use is looked up in: its receiver's, or the one it is written in; null if neither. */
    private ElementId scopeOf(Node use) {
        var receiver = use instanceof MethodCallExpr call ? call.getScope()
                : use instanceof FieldAccessExpr access ? Optional.of(access.getScope()) : Optional.<Expression>empty();
        if (receiver.isEmpty()) {
            return use.findAncestor(TypeDeclaration.class, t -> t.getFullyQualifiedName().isPresent())
                    .map(t -> type((String) t.getFullyQualifiedName().orElseThrow())).orElse(null);
        }
        try {
            var type = receiver.get().calculateResolvedType();
            return type.isReferenceType() ? type(type.asReferenceType().getQualifiedName()) : null;
        } catch (RuntimeException unknown) {
            return null;
        }
    }

    /** A library type joins the program with its package, once, in the order the files name them. */
    private void name(String qualified, Map<String, Element> named) {
        var pkg = packageOf.get(qualified);
        named.putIfAbsent(pkg, library.get(pkg));
        named.putIfAbsent(qualified, library.get(qualified));
    }

    /** The element of a resolved type: the workspace's, or a library's, noted as named by the file read. */
    private ElementId named(ResolvedTypeDeclaration type, Set<String> named) {
        if (type == null) return null;
        var workspace = ids.get(new Address.Type(type.getQualifiedName()));
        if (workspace != null) return workspace;
        if (type.toAst().isPresent()) return null; // declared in the sources, inside a method: as untracked as a local
        var qualified = library(type.getQualifiedName(), type.getPackageName(), type.getName(), kindOf(type));
        named.add(qualified);
        return library.get(qualified).id();
    }

    /** A type of a library, and its package, known from now on: public, and its own name as its identity. */
    private String library(String qualified, String pkg, String name, Kind kind) {
        library.computeIfAbsent(pkg, p -> new Element(new ElementId("lib:" + p), Kind.PACKAGE, p, null,
                new Address.Package(p), p, Map.of(Fact.LIBRARY, "true")));
        library.computeIfAbsent(qualified, q -> new Element(new ElementId("lib:" + q), kind, name, library.get(pkg).id(),
                new Address.Type(q), q, Map.of(Fact.LIBRARY, "true", Fact.VISIBILITY, "public")));
        packageOf.put(qualified, pkg);
        return qualified;
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
        var type = resolveTypeDeclaration(name);
        return type == null ? null : type.getQualifiedName();
    }

    /** A name used as a type: the type it names, in the workspace or in a library, or null. */
    public ResolvedTypeDeclaration resolveTypeDeclaration(NameExpr name) {
        if (name.getParentNode().filter(p -> p instanceof MethodCallExpr || p instanceof FieldAccessExpr).isEmpty()) return null;
        try {
            var type = JavaParserFactory.getContext(name, typeSolver).solveType(name.getNameAsString());
            return type.isSolved() ? type.getCorrespondingDeclaration() : null;
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

    /**
     * The innermost element containing the node: a parameter (its declared type), a method or field, else its type.
     * A type declared inside a method is no element: what is inside it is inside the method.
     */
    public ElementId siteOf(Node node) {
        var type = node.findAncestor(TypeDeclaration.class, t -> t.getFullyQualifiedName().isPresent()).orElseThrow();
        var owner = ids.get(new Address.Type(qualifiedOf(type)));
        var method = node.findAncestor(CallableDeclaration.class, c -> c.findAncestor(TypeDeclaration.class).orElse(null) == type);
        if (method.isPresent()) {
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
