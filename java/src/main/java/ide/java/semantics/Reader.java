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
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory;
import ide.java.syntax.Decl;
import ide.java.syntax.Shape;
import ide.java.text.Located;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

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
     * What a reading gives: the program, the node behind each reference and where each element is declared, by file (a
     * file's own maps, not one copy of all of them: a program may have millions), and the files whose uses this
     * reading resolved.
     */
    public record Reading(Program program, Map<String, Map<String, Located>> uses, Map<String, Map<ElementId, Decl>> declared,
                          Set<String> resolved) {

        public boolean declares(ElementId element) {
            return program.contains(element) && fileOf(element).map(declared::get).filter(in -> in.containsKey(element)).isPresent();
        }

        public Decl declaration(ElementId element) {
            var declaration = program.contains(element) ? fileOf(element).map(declared::get).map(in -> in.get(element)).orElse(null) : null;
            if (declaration == null) throw new IllegalArgumentException("no declaration of " + element);
            return declaration;
        }

        public Node use(String reference) {
            var site = program.unresolved(reference).map(Unresolved::site).orElseGet(() -> program.reference(reference).site());
            var use = fileOf(site).map(uses::get).map(in -> in.get(reference)).orElse(null);
            if (use == null) throw new IllegalArgumentException("no use " + reference);
            return use.node();
        }

        public Map<ElementId, Decl> declarationsIn(String path) {
            return declared.getOrDefault(path, Map.of());
        }

        public Map<String, Located> usesIn(String path) {
            return uses.getOrDefault(path, Map.of());
        }

        /** The file of an element of the sources: its top-level type's. */
        private Optional<String> fileOf(ElementId element) {
            for (var at = element; at != null && program.contains(at); at = program.element(at).parent()) {
                var file = program.element(at).fact(Fact.FILE);
                if (file != null) return Optional.of(file);
            }
            return Optional.empty();
        }
    }

    /** What one file declares: its shape, its elements, and where each is declared. */
    private record Declared(Shape shape, List<Element> elements, Map<ElementId, Decl> declarations, Map<Integer, ElementId> variables) {
    }

    /**
     * What the uses of one file bind: references, uses that bind nothing, their nodes, the library types named, and
     * the names of the uses the program does not follow (members of libraries, locals, calls that do not compile).
     */
    private record Bound(List<Reference> references, List<Unresolved> unresolved, Map<String, Located> nodes, List<String> library,
                         Set<String> untracked) {
    }

    /** What the last reading knew of a file, to keep what a change cannot have affected. */
    private record Part(Unit unit, String pkg, List<String> types, Declared declared, Bound bound) {
    }

    private final Identity ids;
    private final Keys keys;
    private final WorkspaceTypeSolver typeSolver;
    private final Map<String, Element> library = new HashMap<>(); // the libraries' packages and types, by name
    private final Map<String, String> packageOf = new HashMap<>(); // a library type's package
    private Map<String, Part> parts = Map.of();
    private Program last;

    /** What compiling some files says their names bind (none, until told how to compile). */
    private Function<Set<String>, Map<String, EcjBindings.File>> compiled = paths -> Map.of();

    /** How to go over units in turn, their trees parsed ahead (each unit's, when asked for, by default). */
    private InTurn inTurn = (units, parsed, each) -> units.forEach(each);

    public interface InTurn {
        void over(List<Unit> units, java.util.function.Predicate<Unit> parsed, java.util.function.Consumer<Unit> each);
    }

    public void inTurn(InTurn inTurn) {
        this.inTurn = inTurn;
    }

    /** How to learn what the names of some files bind from compiling them. */
    public void compiledBy(Function<Set<String>, Map<String, EcjBindings.File>> compiled) {
        this.compiled = compiled;
    }

    /**
     * Whether the program is read from Eclipse's DOM and compiler, with no JavaParser: the default; the reading that
     * parses with JavaParser stays to compare with ({@code -Dide.java.reader=javaparser}, see ParityProbe).
     */
    private final boolean dom = !"javaparser".equals(System.getProperty("ide.java.reader", "dom"));

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
        var elements = new ArrayList<Element>();
        var packages = new LinkedHashMap<String, ElementId>();
        var packageOf = new HashMap<String, String>(); // a file read before keeps its package and types: its tree may be gone
        var typesOf = new HashMap<String, List<String>>();
        var declaring = new HashMap<String, Unit>();
        var all = List.copyOf(units);
        inTurn.over(all, unit -> fresh.contains(unit.path()) && !dom, unit -> { // types first: members key their parameter types by type id
            if (fresh.contains(unit.path()) && dom) {
                var tree = unit.dom();
                packageOf.put(unit.path(), tree.getPackage() == null ? "" : tree.getPackage().getName().getFullyQualifiedName());
                typesOf.put(unit.path(), DomSyntax.types(tree).stream().map(DomSyntax::qualified).toList());
                for (var type : typesOf.get(unit.path())) ids.of(new Address.Type(type), Kind.CLASS);
            } else if (fresh.contains(unit.path())) {
                var tree = unit.tree();
                packageOf.put(unit.path(), tree.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(""));
                typesOf.put(unit.path(), types(tree).stream().map(Reader::qualifiedOf).toList());
                for (var type : typesOf.get(unit.path())) ids.of(new Address.Type(type), Kind.CLASS);
            } else {
                packageOf.put(unit.path(), previous.get(unit.path()).pkg());
                typesOf.put(unit.path(), previous.get(unit.path()).types());
            }
            var pkg = packageOf.get(unit.path());
            if (!pkg.isEmpty()) packages.computeIfAbsent(pkg, n -> addPackage(elements, n));
            for (var type : typesOf.get(unit.path())) declaring.put(type, unit);
        });
        typeSolver.declaredIn(declaring); // what the solver looks types up in, without reading every tree again
        for (var name : emptyPackages) packages.computeIfAbsent(name, n -> addPackage(elements, n));
        var declared = new LinkedHashMap<String, Declared>();
        var bindings = new HashMap<String, EcjBindings.File>(); // with the DOM, what the compiler says comes first: keys too
        if (dom && !fresh.isEmpty()) bindings.putAll(compiled.apply(fresh));
        inTurn.over(all, unit -> fresh.contains(unit.path()) && !dom, unit -> declared.put(unit.path(), !fresh.contains(unit.path())
                ? previous.get(unit.path()).declared() : dom ? declareDom(unit, packages, bindings.getOrDefault(unit.path(), EcjBindings.File.NONE))
                : declare(unit, packages)));
        // uses only once every element has its id: a use may point into a file read later
        var resolve = affected(units, fresh, previous, declared);
        var bound = new LinkedHashMap<String, Bound>();
        var missing = new TreeSet<>(resolve);
        missing.removeAll(bindings.keySet());
        if (!missing.isEmpty()) bindings.putAll(compiled.apply(missing));
        inTurn.over(all, unit -> resolve.contains(unit.path()) && !dom, unit -> bound.put(unit.path(), !resolve.contains(unit.path())
                ? previous.get(unit.path()).bound() : dom ? bindDom(unit, bindings.getOrDefault(unit.path(), EcjBindings.File.NONE),
                declared.get(unit.path()).variables()) : bind(unit, bindings.getOrDefault(unit.path(), EcjBindings.File.NONE))));
        Compare.report();
        var declarations = new HashMap<String, Map<ElementId, Decl>>();
        declared.forEach((path, d) -> {
            elements.addAll(d.elements());
            declarations.put(path, Collections.unmodifiableMap(d.declarations()));
        });
        var references = new ArrayList<Reference>();
        var unresolved = new ArrayList<Unresolved>();
        var nodes = new HashMap<String, Map<String, Located>>();
        var named = new LinkedHashMap<String, Element>(); // the library types named, with their packages, in order
        for (var entry : bound.entrySet()) {
            var b = entry.getValue();
            references.addAll(b.references());
            unresolved.addAll(b.unresolved());
            nodes.put(entry.getKey(), Collections.unmodifiableMap(b.nodes()));
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
        for (var unit : units) next.put(unit.path(), new Part(unit, packageOf.get(unit.path()), typesOf.get(unit.path()), declared.get(unit.path()), bound.get(unit.path())));
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
        var typesGone = new HashSet<String>(); // types added or removed (a rename is both), by qualified name
        for (var path : paths) {
            var before = previous.containsKey(path) ? previous.get(path).declared().shape() : Shape.NONE;
            var after = declared.containsKey(path) ? declared.get(path).shape() : Shape.NONE;
            var types = before.typesAddedOrRemoved(after);
            if (types == null) return all; // a type that is still there changed: what it extends, anything
            typesGone.addAll(types);
            changed.addAll(before.membersChanged(after));
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
        affected.addAll(namingTypes(units, typesGone));
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

    /**
     * The files a type added or removed can change: those that write its simple name (a use, an import, what a class
     * extends) or the name of a type below it, and those with a use of what it declared. A project of hundreds of
     * files is not resolved again whole for one renamed type.
     */
    private Set<String> namingTypes(Collection<Unit> units, Set<String> types) {
        if (types.isEmpty()) return Set.of();
        var names = new HashSet<String>();
        var gone = new HashSet<ElementId>(); // the types removed, and those below them
        for (var qualified : types) {
            names.add(qualified.substring(qualified.lastIndexOf('.') + 1));
            last.find(qualified).ifPresent(type -> {
                for (var pending = new ArrayDeque<>(List.of(type)); !pending.isEmpty(); ) {
                    var below = pending.poll();
                    if (gone.add(below.id())) {
                        names.add(below.name());
                        pending.addAll(last.subtypes(below));
                    }
                }
            });
        }
        var written = java.util.regex.Pattern.compile("\\b(" + String.join("|", names.stream().map(java.util.regex.Pattern::quote).toList()) + ")\\b");
        var naming = new TreeSet<String>();
        for (var unit : units) if (written.matcher(unit.text()).find()) naming.add(unit.path());
        for (var type : gone) { // and the files with a use of what it declared, found by the index, not by every use
            var targets = new ArrayList<ElementId>(List.of(type));
            last.children(type).forEach(member -> targets.add(member.id()));
            for (var target : targets) {
                for (var use : last.referencesTo(target)) {
                    for (var at = last.contains(use.site()) ? last.element(use.site()) : null; at != null;
                         at = at.parent() == null || !last.contains(at.parent()) ? null : last.element(at.parent())) {
                        if (at.fact(Fact.FILE) != null) {
                            naming.add(at.fact(Fact.FILE));
                            break;
                        }
                    }
                }
            }
        }
        var there = new HashSet<String>();
        units.forEach(unit -> there.add(unit.path()));
        naming.retainAll(there); // a file the type was in may be gone with it
        return naming;
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
                    facts.put(Fact.INITIALIZED, String.valueOf(variable.getInitializer().isPresent()));
                    var id = add(elements, new Address.Field(typeId, variable.getNameAsString()), Kind.FIELD,
                            variable.getNameAsString(), typeId, facts);
                    declarations.put(id, new Decl(unit, field));
                }
            }
            for (var constructor : type.getConstructors()) {
                var id = add(elements, new Address.Callable(typeId, "new", keys.of(constructor)), Kind.CONSTRUCTOR,
                        type.getNameAsString(), typeId, thrown(constructor, new HashMap<>(attributes(constructor))));
                declarations.put(id, new Decl(unit, constructor));
                addParams(elements, declarations, unit, constructor, id);
                addLocals(elements, declarations, unit, constructor, id);
                addStatements(elements, declarations, unit, constructor, id);
            }
            for (var method : type.getMethods()) {
                var facts = thrown(method, new HashMap<>(attributes(method)));
                facts.put(Fact.TYPE, method.getType().asString());
                method.getBody().ifPresent(b -> facts.put(Fact.BODY, b.toString().replaceAll("\\s+", " ")));
                if (kindOf(type) == Kind.INTERFACE && method.getAccessSpecifier().asString().isEmpty()) {
                    facts.put(Fact.VISIBILITY, "public"); // interface members are implicitly public
                }
                if (kindOf(type) == Kind.INTERFACE && method.getBody().isEmpty() && !method.isStatic()) {
                    facts.put(Fact.ABSTRACT, "true"); // and abstract, unless they have a body
                }
                var id = add(elements, new Address.Callable(typeId, method.getNameAsString(), keys.of(method)), Kind.METHOD,
                        method.getNameAsString(), typeId, facts);
                declarations.put(id, new Decl(unit, method));
                addParams(elements, declarations, unit, method, id);
                addLocals(elements, declarations, unit, method, id);
                addStatements(elements, declarations, unit, method, id);
            }
        }
        return new Declared(Shape.of(unit), elements, declarations, Map.of());
    }

    // ---- read from Eclipse's DOM, with what the compiler bound: no JavaParser

    /** The JavaParser node a DOM node stands for, where primitives still find it: by its kind and where it is. */
    private static Located at(Unit unit, int start, int end, Class<? extends Node> kind) {
        return new Located(unit, start, end, kind);
    }

    /** The JavaParser kind of a type declaration. */
    private static Class<? extends Node> javaParserKind(org.eclipse.jdt.core.dom.AbstractTypeDeclaration type) {
        return switch (type) {
            case org.eclipse.jdt.core.dom.EnumDeclaration e -> com.github.javaparser.ast.body.EnumDeclaration.class;
            case org.eclipse.jdt.core.dom.RecordDeclaration r -> com.github.javaparser.ast.body.RecordDeclaration.class;
            case org.eclipse.jdt.core.dom.AnnotationTypeDeclaration a -> com.github.javaparser.ast.body.AnnotationDeclaration.class;
            default -> ClassOrInterfaceDeclaration.class;
        };
    }

    /** A callable's parameter keys, as the compiler resolved them; as written if it could not. */
    private List<String> keysOf(org.eclipse.jdt.core.dom.MethodDeclaration method, EcjBindings.File file) {
        var name = method.getName();
        var declared = file.declaration(name.getStartPosition(), DomSyntax.end(name));
        if (declared != null) return keys.described(declared.parameters());
        var written = new ArrayList<String>();
        for (var parameter : method.parameters()) written.add(DomSyntax.written((org.eclipse.jdt.core.dom.SingleVariableDeclaration) parameter));
        return keys.described(written);
    }

    private Declared declareDom(Unit unit, Map<String, ElementId> packages, EcjBindings.File file) {
        var elements = new ArrayList<Element>();
        var declarations = new HashMap<ElementId, Decl>();
        var variables = new HashMap<Integer, ElementId>();
        var text = unit.text();
        var tree = unit.dom();
        var pkg = tree.getPackage() == null ? null : packages.get(tree.getPackage().getName().getFullyQualifiedName());
        for (var type : DomSyntax.types(tree)) {
            var typeName = DomSyntax.qualified(type);
            var typeId = ids.get(new Address.Type(typeName));
            var parent = type.getParent() instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration outer
                    ? ids.get(new Address.Type(DomSyntax.qualified(outer))) : pkg;
            var typeFacts = new HashMap<>(DomSyntax.attributes(type, text));
            if (type.isPackageMemberTypeDeclaration()) typeFacts.put(Fact.FILE, unit.path());
            if (type instanceof org.eclipse.jdt.core.dom.TypeDeclaration c && !c.isInterface() && c.getSuperclassType() != null) {
                typeFacts.put(Fact.SUPERCLASS, DomSyntax.written(c.getSuperclassType()));
            }
            var kind = DomSyntax.kindOf(type);
            elements.add(new Element(typeId, kind, type.getName().getIdentifier(), parent, new Address.Type(typeName), typeName,
                    Map.copyOf(typeFacts)));
            declarations.put(typeId, new Decl(at(unit, DomSyntax.start(type, text), DomSyntax.end(type), javaParserKind(type))));
            for (var field : DomSyntax.fields(type)) {
                for (var fragment : field.fragments()) {
                    var variable = (org.eclipse.jdt.core.dom.VariableDeclarationFragment) fragment;
                    var facts = new HashMap<>(DomSyntax.attributes(field, text));
                    facts.put(Fact.TYPE, DomSyntax.written(field.getType(), variable.getExtraDimensions()));
                    facts.put(Fact.INITIALIZED, String.valueOf(variable.getInitializer() != null));
                    var name = variable.getName().getIdentifier();
                    var id = add(elements, new Address.Field(typeId, name), Kind.FIELD, name, typeId, facts);
                    declarations.put(id, new Decl(at(unit, DomSyntax.start(field, text), DomSyntax.end(field), FieldDeclaration.class)));
                }
            }
            for (var constructor : DomSyntax.constructors(type)) {
                var id = add(elements, new Address.Callable(typeId, "new", keysOf(constructor, file)), Kind.CONSTRUCTOR,
                        type.getName().getIdentifier(), typeId, thrownDom(constructor, new HashMap<>(DomSyntax.attributes(constructor, text))));
                declarations.put(id, new Decl(at(unit, DomSyntax.start(constructor, text), DomSyntax.end(constructor),
                        com.github.javaparser.ast.body.ConstructorDeclaration.class)));
                addBodyDom(elements, declarations, variables, unit, constructor, id);
            }
            for (var method : DomSyntax.methods(type)) {
                var facts = thrownDom(method, new HashMap<>(DomSyntax.attributes(method, text)));
                facts.put(Fact.TYPE, method.getReturnType2() == null ? "void" : DomSyntax.written(method.getReturnType2()));
                if (method.getBody() != null) facts.put(Fact.BODY, DomSyntax.textOf(method.getBody(), text));
                if (kind == Kind.INTERFACE && (method.getModifiers() & (org.eclipse.jdt.core.dom.Modifier.PUBLIC
                        | org.eclipse.jdt.core.dom.Modifier.PROTECTED | org.eclipse.jdt.core.dom.Modifier.PRIVATE)) == 0) {
                    facts.put(Fact.VISIBILITY, "public"); // interface members are implicitly public
                }
                if (kind == Kind.INTERFACE && method.getBody() == null && !org.eclipse.jdt.core.dom.Modifier.isStatic(method.getModifiers())) {
                    facts.put(Fact.ABSTRACT, "true"); // and abstract, unless they have a body
                }
                var name = method.getName().getIdentifier();
                var id = add(elements, new Address.Callable(typeId, name, keysOf(method, file)), Kind.METHOD, name, typeId, facts);
                declarations.put(id, new Decl(at(unit, DomSyntax.start(method, text), DomSyntax.end(method), com.github.javaparser.ast.body.MethodDeclaration.class)));
                addBodyDom(elements, declarations, variables, unit, method, id);
            }
        }
        return new Declared(DomSyntax.shape(unit), elements, declarations, variables);
    }

    /** A callable's parameters, locals and statements, as the JavaParser reading has them. */
    private void addBodyDom(List<Element> elements, Map<ElementId, Decl> declarations, Map<Integer, ElementId> variables, Unit unit,
                            org.eclipse.jdt.core.dom.MethodDeclaration callable, ElementId owner) {
        var text = unit.text();
        for (var each : callable.parameters()) {
            var parameter = (org.eclipse.jdt.core.dom.SingleVariableDeclaration) each;
            var name = parameter.getName().getIdentifier();
            var id = add(elements, new Address.Param(owner, name), Kind.PARAM, name, owner, Map.of(Fact.TYPE, DomSyntax.written(parameter)));
            declarations.put(id, new Decl(at(unit, parameter.getStartPosition(), DomSyntax.end(parameter), com.github.javaparser.ast.body.Parameter.class)));
            variables.put(parameter.getName().getStartPosition(), id);
        }
        var seen = new HashMap<String, Integer>();
        for (var local : DomSyntax.locals(callable, text)) {
            var ordinal = seen.merge(local.name(), 1, Integer::sum) - 1;
            var id = add(elements, new Address.Local(owner, local.name(), ordinal), Kind.LOCAL, local.name(), owner, Map.of(Fact.TYPE, local.type()));
            declarations.put(id, new Decl(at(unit, local.start(), local.end(), VariableDeclarator.class)));
            variables.put(local.nameStart(), id);
        }
        addStatementsDom(elements, declarations, unit, DomSyntax.statements(callable), owner);
    }

    private void addStatementsDom(List<Element> elements, Map<ElementId, Decl> declarations, Unit unit,
                                  List<org.eclipse.jdt.core.dom.Statement> statements, ElementId owner) {
        for (var i = 0; i < statements.size(); i++) {
            var statement = statements.get(i);
            var id = add(elements, new Address.Statement(owner, i), Kind.STATEMENT, "", owner, Map.of());
            declarations.put(id, new Decl(at(unit, statement.getStartPosition(), DomSyntax.end(statement), Statement.class)));
            addStatementsDom(elements, declarations, unit, DomSyntax.nested(statement), id);
        }
    }

    /** What the uses of one file bind, read from the DOM with what the compiler bound: as {@link #bind} does with JavaParser. */
    private Bound bindDom(Unit unit, EcjBindings.File file, Map<Integer, ElementId> variables) {
        var references = new ArrayList<Reference>();
        var unresolved = new ArrayList<Unresolved>();
        var nodes = new HashMap<String, Located>();
        var named = new LinkedHashSet<String>();
        var untracked = new HashSet<String>();
        var ordinals = new HashMap<ElementId, Integer>();
        var callables = new IdentityHashMap<org.eclipse.jdt.core.dom.MethodDeclaration, ElementId>();
        var text = unit.text();
        var tree = unit.dom();
        for (var use : DomSyntax.uses(tree)) {
            var typeName = false;
            var bindsNothing = false;
            ElementId target = null;
            ElementId scope = null;
            var bound = switch (use.category()) {
                case DomSyntax.TYPE -> use.typeExpression() && file.type(use.nameStart(), use.nameEnd()) == null
                        ? file.name(use.nameStart(), use.nameEnd()) : file.type(use.nameStart(), use.nameEnd()); // before ::, a name to the compiler
                case DomSyntax.NEW -> file.constructor(use.nameStart(), use.nameEnd());
                case DomSyntax.CONSTRUCTOR_CALL -> file.constructor(use.start(), use.start());
                case DomSyntax.METHOD_REFERENCE -> file.name(use.nameStart(), use.end());
                default -> file.name(use.nameStart(), use.nameEnd());
            };
            if (bound == null && use.category() == DomSyntax.TYPE && !use.prefix()) { // the compiler said nothing: one it cannot reach from here
                target = writtenType(tree, use.name());
            }
            if (bound != null) switch (bound.kind()) {
                case TYPE -> {
                    target = typeOrLibrary(bound, named);
                    typeName = use.category() == DomSyntax.NAME || use.category() == DomSyntax.FIELD_ACCESS;
                }
                case FIELD -> target = memberOf(bound, owner -> new Address.Field(owner, bound.name()));
                case METHOD -> target = memberOf(bound, owner -> new Address.Callable(owner, bound.name(), keys.described(bound.parameters())));
                case CONSTRUCTOR -> target = memberOf(bound, owner -> new Address.Callable(owner, "new", keys.described(bound.parameters())));
                case LOCAL, PARAM -> target = variables.get(bound.declaredAt());
                case NOTHING -> {
                    var written = use.category() == DomSyntax.TYPE ? writtenType(tree, use.name()) : null;
                    if (written != null) target = written; // a type of the workspace its import names, one it cannot reach
                    else {
                        bindsNothing = nothingHere(use);
                        scope = bound.owner() == null ? null : type(bound.owner());
                    }
                }
            }
            var name = use.category() == DomSyntax.CONSTRUCTOR_CALL ? constructedDom(use) : use.name();
            var place = target == null && !bindsNothing ? null : placeDom(use, file, callables);
            var site = place == null ? null : place.member();
            if (site == null) { // members of libraries, locals, what does not compile, in declarations it cannot read
                untracked.add(name);
                continue;
            }
            var id = site + "@" + (ordinals.merge(site, 1, Integer::sum) - 1);
            nodes.put(id, at(unit, use.start(), use.end(), javaParserKind(use)));
            var mode = typeName ? Mode.TYPE : modeDom(use);
            var receiver = target != null && (target.value().startsWith(Kind.LOCAL.prefix()) || target.value().startsWith(Kind.PARAM.prefix()))
                    ? Reference.Receiver.NONE : receiverDom(use, mode, file);
            if (bindsNothing) unresolved.add(new Unresolved(id, site, name, mode, use.category() == DomSyntax.TYPE ? null : scope, place.statement()));
            else references.add(new Reference(id, site, target, name, mode, receiver, place.statement()));
        }
        return new Bound(references, unresolved, nodes, List.copyOf(named), Set.copyOf(untracked));
    }

    /** A workspace type a simple name names in a file, as its imports and its package say; null if none. */
    private ElementId writtenType(org.eclipse.jdt.core.dom.CompilationUnit tree, String simple) {
        for (var each : tree.imports()) {
            var imported = (org.eclipse.jdt.core.dom.ImportDeclaration) each;
            var name = imported.getName().getFullyQualifiedName();
            if (!imported.isOnDemand() && !imported.isStatic() && name.endsWith("." + simple)) return type(name);
        }
        var pkg = tree.getPackage() == null ? "" : tree.getPackage().getName().getFullyQualifiedName() + ".";
        return type(pkg + simple);
    }

    private ElementId typeOrLibrary(EcjBindings.Bound bound, Set<String> named) {
        var workspace = type(bound.owner());
        if (workspace != null || bound.pkg() == null) return workspace;
        var simple = bound.pkg().isEmpty() ? bound.owner() : bound.owner().substring(bound.pkg().length() + 1);
        var kind = switch (bound.name()) {
            case "interface", "annotation" -> Kind.INTERFACE;
            case "enum" -> Kind.ENUM;
            default -> Kind.CLASS;
        };
        var qualified = library(bound.owner(), bound.pkg(), simple, kind);
        named.add(qualified);
        return library.get(qualified).id();
    }

    /** A member the compiler bound: its id, or null for a library's (or one whose id is not found). */
    private ElementId memberOf(EcjBindings.Bound bound, java.util.function.Function<ElementId, Address> address) {
        var owner = type(bound.owner());
        return owner == null ? null : ids.get(address.apply(owner));
    }

    /** Whether a use that binds nothing is one the program keeps: not a receiver, a switch's label or an annotation's. */
    private static boolean nothingHere(DomSyntax.Use use) {
        if (use.category() == DomSyntax.TYPE) return !use.prefix() && !use.typeExpression();
        if (use.category() != DomSyntax.NAME && use.category() != DomSyntax.FIELD_ACCESS) return true;
        var node = use.node();
        var location = node.getLocationInParent();
        if (location == org.eclipse.jdt.core.dom.QualifiedName.QUALIFIER_PROPERTY || location == org.eclipse.jdt.core.dom.FieldAccess.EXPRESSION_PROPERTY
                || location == org.eclipse.jdt.core.dom.MethodInvocation.EXPRESSION_PROPERTY || node.getParent() instanceof org.eclipse.jdt.core.dom.SwitchCase) {
            return false;
        }
        for (var at = node.getParent(); at != null; at = at.getParent()) if (at instanceof org.eclipse.jdt.core.dom.Annotation) return false;
        return true;
    }

    private static Class<? extends Node> javaParserKind(DomSyntax.Use use) {
        return switch (use.category()) {
            case DomSyntax.NAME -> NameExpr.class;
            case DomSyntax.FIELD_ACCESS -> FieldAccessExpr.class;
            case DomSyntax.CALL -> MethodCallExpr.class;
            case DomSyntax.TYPE -> ClassOrInterfaceType.class;
            case DomSyntax.NEW -> ObjectCreationExpr.class;
            case DomSyntax.CONSTRUCTOR_CALL -> ExplicitConstructorInvocationStmt.class;
            default -> MethodReferenceExpr.class;
        };
    }

    /** The simple name of the type a {@code super(...)} or {@code this(...)} makes, as its constructor is named. */
    private static String constructedDom(DomSyntax.Use use) {
        org.eclipse.jdt.core.dom.TypeDeclaration type = null;
        for (var at = use.node().getParent(); at != null && type == null; at = at.getParent()) {
            if (at instanceof org.eclipse.jdt.core.dom.TypeDeclaration t) type = t;
        }
        if (use.name().equals("this") || type == null) return type == null ? "this" : type.getName().getIdentifier();
        var superclass = type.getSuperclassType() == null ? null : DomSyntax.typeName(type.getSuperclassType());
        return superclass == null ? "Object" : DomSyntax.simple(superclass);
    }

    private static Mode modeDom(DomSyntax.Use use) {
        return switch (use.category()) {
            case DomSyntax.CALL, DomSyntax.METHOD_REFERENCE, DomSyntax.CONSTRUCTOR_CALL -> Mode.CALL;
            case DomSyntax.NEW -> Mode.NEW;
            case DomSyntax.TYPE -> linkModeDom(use);
            default -> {
                var node = use.node();
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.Assignment assign && assign.getLeftHandSide() == node) {
                    yield assign.getOperator() == org.eclipse.jdt.core.dom.Assignment.Operator.ASSIGN ? Mode.WRITE : Mode.UPDATE;
                }
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.PostfixExpression) yield Mode.UPDATE;
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.PrefixExpression prefix
                        && (prefix.getOperator() == org.eclipse.jdt.core.dom.PrefixExpression.Operator.INCREMENT
                        || prefix.getOperator() == org.eclipse.jdt.core.dom.PrefixExpression.Operator.DECREMENT)) {
                    yield Mode.UPDATE;
                }
                yield Mode.READ;
            }
        };
    }

    /** A type mention is a link when it extends, implements, or is the whole declared type of a field, parameter or method. */
    private static Mode linkModeDom(DomSyntax.Use use) {
        if (use.prefix() || use.typeExpression()) return Mode.TYPE;
        var node = use.node();
        var location = node.getLocationInParent();
        var parent = node.getParent();
        if (location == org.eclipse.jdt.core.dom.TypeDeclaration.SUPERCLASS_TYPE_PROPERTY) return Mode.EXTENDS;
        if (location == org.eclipse.jdt.core.dom.TypeDeclaration.SUPER_INTERFACE_TYPES_PROPERTY) {
            return ((org.eclipse.jdt.core.dom.TypeDeclaration) parent).isInterface() ? Mode.EXTENDS : Mode.IMPLEMENTS;
        }
        if (location == org.eclipse.jdt.core.dom.EnumDeclaration.SUPER_INTERFACE_TYPES_PROPERTY
                || location == org.eclipse.jdt.core.dom.RecordDeclaration.SUPER_INTERFACE_TYPES_PROPERTY) {
            return Mode.IMPLEMENTS;
        }
        if (location == org.eclipse.jdt.core.dom.SingleVariableDeclaration.TYPE_PROPERTY) { // what JavaParser calls a parameter
            var holder = parent.getParent();
            return holder instanceof org.eclipse.jdt.core.dom.MethodDeclaration || holder instanceof org.eclipse.jdt.core.dom.LambdaExpression
                    || holder instanceof org.eclipse.jdt.core.dom.CatchClause || holder instanceof org.eclipse.jdt.core.dom.RecordDeclaration
                    ? Mode.DECLARED : Mode.TYPE;
        }
        if (location == org.eclipse.jdt.core.dom.MethodDeclaration.RETURN_TYPE2_PROPERTY || location == org.eclipse.jdt.core.dom.FieldDeclaration.TYPE_PROPERTY) {
            return Mode.DECLARED;
        }
        return Mode.TYPE;
    }

    /** What a use is made on, as it is written, the compiler saying whether a name before it is a type. */
    private static Reference.Receiver receiverDom(DomSyntax.Use use, Mode mode, EcjBindings.File file) {
        if (mode.isTypeUse() || mode == Mode.NEW) return Reference.Receiver.NONE;
        var scope = use.scope();
        if (use.category() == DomSyntax.METHOD_REFERENCE) {
            if (scope instanceof org.eclipse.jdt.core.dom.Name || scope instanceof org.eclipse.jdt.core.dom.Type) return Reference.Receiver.TYPE;
        }
        if (scope == null || scope == use.node() || scope instanceof org.eclipse.jdt.core.dom.ThisExpression
                || use.node() instanceof org.eclipse.jdt.core.dom.SuperMethodReference) {
            return Reference.Receiver.SELF;
        }
        if (scope instanceof org.eclipse.jdt.core.dom.Name name) {
            var last = DomSyntax.last(name);
            var bound = file.name(last.getStartPosition(), DomSyntax.end(last));
            if (bound != null && bound.kind() == EcjBindings.Kind.TYPE) return Reference.Receiver.TYPE;
            if (bound != null && bound.kind() != EcjBindings.Kind.NOTHING) return Reference.Receiver.OTHER;
            return name instanceof org.eclipse.jdt.core.dom.QualifiedName ? Reference.Receiver.TYPE : Reference.Receiver.OTHER;
        }
        return Reference.Receiver.OTHER;
    }

    /** Where a use is made: the member, parameter or type that makes it, and the statement of a body it is in. */
    private Place placeDom(DomSyntax.Use use, EcjBindings.File file, Map<org.eclipse.jdt.core.dom.MethodDeclaration, ElementId> callables) {
        var node = use.node();
        var type = DomSyntax.typeOf(node);
        if (type == null) return null;
        var owner = ids.get(new Address.Type(DomSyntax.qualified(type)));
        if (use.of() != null && use.of().getParent() instanceof org.eclipse.jdt.core.dom.FieldDeclaration) { // a field's declared type
            return new Place(ids.get(new Address.Field(owner, use.of().getName().getIdentifier())), null);
        }
        org.eclipse.jdt.core.dom.MethodDeclaration method = null;
        for (var at = node.getParent(); at != null && at != type && method == null; at = at.getParent()) {
            if (at instanceof org.eclipse.jdt.core.dom.MethodDeclaration m && nearestType(m) == type && !m.isCompactConstructor()) method = m;
        }
        if (method != null) {
            var found = method;
            var callable = callables.computeIfAbsent(method, m -> ids.get(new Address.Callable(owner,
                    m.isConstructor() ? "new" : m.getName().getIdentifier(), keysOf(m, file))));
            for (var at = node; at != null && at != method; at = at.getParent()) {
                if (at instanceof org.eclipse.jdt.core.dom.SingleVariableDeclaration parameter) {
                    if (parameter.getParent() == found) return new Place(ids.get(new Address.Param(callable, parameter.getName().getIdentifier())), null);
                    break;
                }
            }
            return new Place(callable, callable == null ? null : statementOfDom(node, method, callable));
        }
        for (var at = node.getParent(); at != null && at != type; at = at.getParent()) {
            if (at instanceof org.eclipse.jdt.core.dom.VariableDeclarationFragment fragment) {
                return new Place(fragment.getParent() instanceof org.eclipse.jdt.core.dom.FieldDeclaration
                        ? ids.get(new Address.Field(owner, fragment.getName().getIdentifier())) : owner, null);
            }
        }
        return new Place(owner, null);
    }

    private static org.eclipse.jdt.core.dom.AbstractTypeDeclaration nearestType(org.eclipse.jdt.core.dom.ASTNode node) {
        for (var at = node.getParent(); at != null; at = at.getParent()) {
            if (at instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration type) return type;
        }
        return null;
    }

    /** The innermost statement of a callable's body a node is in, or null. */
    private ElementId statementOfDom(org.eclipse.jdt.core.dom.ASTNode node, org.eclipse.jdt.core.dom.MethodDeclaration method, ElementId callable) {
        var above = Collections.newSetFromMap(new IdentityHashMap<org.eclipse.jdt.core.dom.ASTNode, Boolean>());
        for (var at = node; at != null && at != method; at = at.getParent()) above.add(at);
        var statements = DomSyntax.statements(method);
        ElementId statement = null;
        while (true) {
            int low = 0, high = statements.size() - 1, found = -1;
            while (low <= high) {
                var middle = (low + high) >>> 1;
                if (statements.get(middle).getStartPosition() > node.getStartPosition()) high = middle - 1;
                else {
                    found = middle;
                    low = middle + 1;
                }
            }
            if (found < 0 || !above.contains(statements.get(found))) return statement;
            var held = ids.get(new Address.Statement(statement == null ? callable : statement, found));
            if (held == null) return statement;
            statement = held;
            statements = DomSyntax.nested(statements.get(found));
        }
    }

    private static HashMap<Fact, String> thrownDom(org.eclipse.jdt.core.dom.MethodDeclaration callable, HashMap<Fact, String> facts) {
        if (!callable.thrownExceptionTypes().isEmpty()) {
            facts.put(Fact.THROWS, String.join(", ", callable.thrownExceptionTypes().stream()
                    .map(t -> DomSyntax.written((org.eclipse.jdt.core.dom.Type) t)).toList()));
        }
        return facts;
    }

    /** The facts of a callable, with what it declares it throws, as written. */
    private static HashMap<Fact, String> thrown(com.github.javaparser.ast.body.CallableDeclaration<?> callable, HashMap<Fact, String> facts) {
        if (callable.getThrownExceptions().isNonEmpty()) {
            facts.put(Fact.THROWS, String.join(", ", callable.getThrownExceptions().stream().map(t -> t.asString()).toList()));
        }
        return facts;
    }

    /** What the uses of one file bind; a use's id is its site and its place among the site's uses. */
    private Bound bind(Unit unit, EcjBindings.File file) {
        ordinals = new IdentityHashMap<>();
        try {
            return bound(unit, file);
        } finally {
            ordinals = null;
        }
    }

    private Bound bound(Unit unit, EcjBindings.File file) {
        var compiler = new Compiled(unit, file);
        var references = new ArrayList<Reference>();
        var unresolved = new ArrayList<Unresolved>();
        var nodes = new HashMap<String, Located>();
        var named = new LinkedHashSet<String>();
        var untracked = new HashSet<String>();
        var ordinals = new HashMap<ElementId, Integer>();
        var cu = unit.tree();
        var uses = new ArrayList<Node>();
        uses.addAll(fieldUses(cu));
        uses.addAll(cu.findAll(MethodCallExpr.class));
        uses.addAll(cu.findAll(ClassOrInterfaceType.class));
        uses.addAll(cu.findAll(ObjectCreationExpr.class));
        uses.addAll(cu.findAll(ExplicitConstructorInvocationStmt.class));
        uses.addAll(cu.findAll(MethodReferenceExpr.class));
        uses.sort(Comparator.comparing(n -> n.getBegin().orElseThrow()));
        for (var use : uses) {
            var typeName = false;
            var bindsNothing = false;
            ElementId target;
            var known = Compare.MODE == Compare.Mode.SOLVER ? null : compiler.target(use, named);
            if (known != null && Compare.MODE == Compare.Mode.COMPILER) {
                target = known.target();
                typeName = known.typeName();
            } else switch (use) {
                case MethodCallExpr call -> {
                    target = resolveMethod(call);
                    bindsNothing = target == null && callBindsNothing(call);
                }
                case ObjectCreationExpr creation -> {
                    target = callable(resolveConstructor(creation));
                    bindsNothing = target == null && constructorBindsNothing(creation, this::workspaceType);
                }
                case MethodReferenceExpr reference -> target = callable(resolveMethodReference(reference));
                case ExplicitConstructorInvocationStmt invocation -> target = callable(resolveConstructor(invocation));
                case ClassOrInterfaceType type -> {
                    var declaration = resolveDeclaration(type);
                    target = named(declaration, named);
                    bindsNothing = declaration == null && bindsNothing(type);
                }
                default -> {
                    var field = resolveField((Expression) use);
                    var local = field == null && use instanceof NameExpr name && resolveLocal(name) instanceof VariableDeclarator declarator ? local(declarator)
                            : field == null && use instanceof NameExpr name && Resolution.resolveParameter(name) instanceof com.github.javaparser.ast.body.Parameter parameter ? param(parameter) : null;
                    var type = field == null && local == null && use instanceof NameExpr name ? resolveTypeDeclaration(name) : null;
                    typeName = type != null;
                    target = field != null ? field(field) : local != null ? local : named(type, named);
                    bindsNothing = field == null && local == null && type == null && valueBindsNothing((Expression) use);
                }
            }
            if (known != null && Compare.MODE == Compare.Mode.COMPARE) Compare.count(use, unit, known.target(), target);
            var name = use instanceof ObjectCreationExpr creation ? creation.getType().getNameAsString()
                    : use instanceof ExplicitConstructorInvocationStmt invocation ? constructed(invocation)
                    : use instanceof MethodReferenceExpr reference ? reference.getIdentifier()
                    : ((NodeWithSimpleName<?>) use).getNameAsString();
            var place = target == null && !bindsNothing ? null : placeOf(use);
            var site = place == null ? null : place.member();
            if (site == null) { // members of libraries, locals, what does not compile, in declarations it cannot read
                untracked.add(name);
                continue;
            }
            var id = site + "@" + (ordinals.merge(site, 1, Integer::sum) - 1);
            nodes.put(id, new Located(unit, use));
            var mode = typeName ? Mode.TYPE : modeOf(use);
            var receiver = target != null && (target.value().startsWith(Kind.LOCAL.prefix()) || target.value().startsWith(Kind.PARAM.prefix()))
                    ? Reference.Receiver.NONE : receiver(use, mode, compiler);
            if (bindsNothing) unresolved.add(new Unresolved(id, site, name, mode, use instanceof ClassOrInterfaceType ? null : scopeOf(use), place.statement()));
            else references.add(new Reference(id, site, target, name, mode, receiver, place.statement()));
        }
        return new Bound(references, unresolved, nodes, List.copyOf(named), Set.copyOf(untracked));
    }

    /** What the compiler says a use binds: a target (null: what the program does not follow), and whether a name is a type. */
    private record Known(ElementId target, boolean typeName) {
    }

    /**
     * What the compiler bound the names of one file to, as ids. A use it bound to something of the workspace whose id
     * is not found (a key written otherwise) is not known: the solver decides it, rather than a reference lost.
     */
    private final class Compiled {

        private final Unit unit;
        private final EcjBindings.File file;
        private Map<Integer, Node> declared; // where each local's or parameter's name is declared, once asked
        private final Map<Integer, Optional<ElementId>> variables = new HashMap<>(); // each one's id, once: a method may have thousands

        Compiled(Unit unit, EcjBindings.File file) {
            this.unit = unit;
            this.file = file;
        }

        Known target(Node use, Set<String> named) {
            if (file == EcjBindings.File.NONE) return null;
            var bound = switch (use) {
                case MethodCallExpr call -> at(call.getName(), file.names());
                case ObjectCreationExpr creation -> at(creation.getType().getName(), file.constructors());
                case ExplicitConstructorInvocationStmt invocation -> {
                    var at = unit.offsetOf(invocation.getBegin().orElseThrow());
                    yield file.constructor(at, at);
                }
                case MethodReferenceExpr reference -> {
                    var end = unit.offsetOf(reference.getEnd().orElseThrow());
                    yield file.name(end - reference.getIdentifier().length() + 1, end);
                }
                case ClassOrInterfaceType type -> at(type.getName(), file.types());
                case NameExpr name -> at(name.getName(), file.names());
                case FieldAccessExpr access -> at(access.getName(), file.names());
                default -> null;
            };
            if (bound == null) return null;
            return switch (bound.kind()) {
                case TYPE -> {
                    var workspace = type(bound.owner());
                    if (workspace != null) yield new Known(workspace, use instanceof NameExpr || use instanceof FieldAccessExpr);
                    if (bound.owner().equals(bound.pkg()) || bound.pkg() == null) yield null;
                    var simple = bound.pkg().isEmpty() ? bound.owner() : bound.owner().substring(bound.pkg().length() + 1);
                    var kind = switch (bound.name()) {
                        case "interface", "annotation" -> Kind.INTERFACE;
                        case "enum" -> Kind.ENUM;
                        default -> Kind.CLASS;
                    };
                    var qualified = library(bound.owner(), bound.pkg(), simple, kind);
                    named.add(qualified);
                    yield new Known(library.get(qualified).id(), use instanceof NameExpr || use instanceof FieldAccessExpr);
                }
                case FIELD -> member(bound, owner -> new Address.Field(owner, bound.name()));
                case METHOD -> member(bound, owner -> new Address.Callable(owner, bound.name(), keys.described(bound.parameters())));
                case CONSTRUCTOR -> member(bound, owner -> new Address.Callable(owner, "new", keys.described(bound.parameters())));
                case NOTHING -> null; // the solver says
                case LOCAL, PARAM -> {
                    var declaration = declared().get(bound.declaredAt());
                    // a lambda's parameter, or a local of a class inside a method: as untracked as the solver leaves them
                    yield declaration == null ? null : new Known(variables.computeIfAbsent(bound.declaredAt(), at ->
                            Optional.ofNullable(declaration instanceof VariableDeclarator v ? local(v)
                                    : declaration instanceof com.github.javaparser.ast.body.Parameter p ? param(p) : null)).orElse(null), false);
                }
            };
        }

        /** What a scope is, as the compiler says: a type, or another object; null when it does not say. */
        Reference.Receiver receiver(Expression scope) {
            var name = scope instanceof NameExpr n ? n.getName() : scope instanceof FieldAccessExpr f ? f.getName() : null;
            var bound = name == null || file == EcjBindings.File.NONE ? null : at(name, file.names());
            if (bound == null) return null;
            return bound.kind() == EcjBindings.Kind.TYPE ? Reference.Receiver.TYPE : Reference.Receiver.OTHER;
        }

        private Known member(EcjBindings.Bound bound, java.util.function.Function<ElementId, Address> address) {
            var owner = type(bound.owner());
            if (owner == null) return new Known(null, false); // a library's: not followed
            var id = ids.get(address.apply(owner));
            return id == null ? null : new Known(id, false);
        }

        private EcjBindings.Bound at(Node name, Map<Long, EcjBindings.Bound> in) {
            var range = name.getRange().orElse(null);
            if (range == null) return null;
            return in.get(EcjBindings.key(unit.offsetOf(range.begin), unit.offsetOf(range.end)));
        }

        private Map<Integer, Node> declared() {
            if (declared == null) {
                declared = new HashMap<>();
                var cu = unit.tree();
                for (var v : cu.findAll(VariableDeclarator.class)) v.getName().getBegin().ifPresent(b -> declared.put(unit.offsetOf(b), v));
                for (var p : cu.findAll(com.github.javaparser.ast.body.Parameter.class)) {
                    p.getName().getBegin().ifPresent(b -> declared.put(unit.offsetOf(b), p));
                }
            }
            return declared;
        }
    }

    /**
     * Which resolves names: the compiler, the solver when it does not know (the default), the solver alone, or both,
     * counting where they differ ({@code -Dide.java.names=compiler|solver|compare}).
     */
    static final class Compare {

        enum Mode { COMPILER, SOLVER, COMPARE }

        static final Mode MODE = switch (System.getProperty("ide.java.names", "compiler")) {
            case "solver" -> Mode.SOLVER;
            case "compare" -> Mode.COMPARE;
            default -> Mode.COMPILER;
        };

        private static final Map<String, Integer> COUNTS = new java.util.TreeMap<>();
        private static final List<String> SAMPLES = new ArrayList<>();

        static void count(Node use, Unit unit, ElementId compiler, ElementId solver) {
            var what = use.getClass().getSimpleName() + (java.util.Objects.equals(compiler, solver) ? " same"
                    : compiler == null ? " solver-only" : solver == null ? " compiler-only" : " different");
            COUNTS.merge(what, 1, Integer::sum);
            if (!what.endsWith(" same") && SAMPLES.size() < 60) {
                SAMPLES.add(what + " " + unit.path() + ":" + use.getBegin().map(b -> b.line).orElse(0) + " " + use + " compiler=" + compiler + " solver=" + solver);
            }
        }

        static void report() {
            if (MODE != Mode.COMPARE || COUNTS.isEmpty()) return;
            COUNTS.forEach((what, n) -> System.out.println("NAMES " + what + " " + n));
            SAMPLES.forEach(sample -> System.out.println("NAMES sample " + sample));
            COUNTS.clear();
            SAMPLES.clear();
        }
    }

    /** The simple name of the type a {@code super(...)} or {@code this(...)} makes, as its constructor is named. */
    private static String constructed(ExplicitConstructorInvocationStmt invocation) {
        var type = invocation.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        if (invocation.isThis() || type.isEmpty()) return type.map(t -> t.getNameAsString()).orElse("this");
        return type.get().getExtendedTypes().getFirst().map(t -> t.getNameAsString()).orElse("Object");
    }

    /**
     * What a use is made on, as it is written: alone or through {@code this} or {@code super}, the object itself;
     * through a name that is a type and no value, the type; through anything else, another object.
     */
    private Reference.Receiver receiver(Node use, Mode mode, Compiled compiler) {
        if (mode.isTypeUse() || mode == Mode.NEW) return Reference.Receiver.NONE;
        Expression scope = switch (use) {
            case MethodCallExpr call -> call.getScope().orElse(null);
            case FieldAccessExpr access -> access.getScope();
            case MethodReferenceExpr reference -> reference.getScope();
            default -> null; // a name written alone
        };
        if (scope == null || scope instanceof ThisExpr || scope instanceof SuperExpr) return Reference.Receiver.SELF;
        if (scope instanceof TypeExpr) return Reference.Receiver.TYPE;
        var said = Compare.MODE == Compare.Mode.COMPILER ? compiler.receiver(scope) : null;
        if (said != null) return said;
        if (scope instanceof NameExpr name) {
            try {
                name.resolve();
                return Reference.Receiver.OTHER;
            } catch (RuntimeException | StackOverflowError notAValue) {
                return resolveTypeDeclaration(name) != null ? Reference.Receiver.TYPE : Reference.Receiver.OTHER;
            }
        }
        if (scope instanceof FieldAccessExpr qualified) {
            try {
                qualified.calculateResolvedType();
                return Reference.Receiver.OTHER;
            } catch (RuntimeException | StackOverflowError notAValue) { // a qualified name of a type
                return Reference.Receiver.TYPE;
            }
        }
        return Reference.Receiver.OTHER;
    }

    /** Whether a qualified name is a type of the workspace. */
    private boolean workspaceType(String qualified) {
        return type(qualified) != null;
    }

    /** The workspace type a member use is looked up in: its receiver's, or the one it is written in; null if neither. */
    private ElementId scopeOf(Node use) {
        if (use instanceof ObjectCreationExpr creation) {
            try {
                return type(creation.getType().resolve().asReferenceType().getQualifiedName());
            } catch (RuntimeException | StackOverflowError unknown) {
                return null;
            }
        }
        var receiver = use instanceof MethodCallExpr call ? call.getScope()
                : use instanceof FieldAccessExpr access ? Optional.of(access.getScope()) : Optional.<Expression>empty();
        if (receiver.isEmpty()) {
            return use.findAncestor(TypeDeclaration.class, t -> t.getFullyQualifiedName().isPresent())
                    .map(t -> type((String) t.getFullyQualifiedName().orElseThrow())).orElse(null);
        }
        try {
            var type = receiver.get().calculateResolvedType();
            return type.isReferenceType() ? type(type.asReferenceType().getQualifiedName()) : null;
        } catch (RuntimeException | StackOverflowError unknown) {
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

    /**
     * The locals a method or constructor declares (not those of a class or a lambda inside it, which are theirs), in the
     * order they are written: two of one name are told apart by which they are.
     */
    private void addLocals(List<Element> elements, Map<ElementId, Decl> declarations, Unit unit, CallableDeclaration<?> callable,
                           ElementId owner) {
        var seen = new HashMap<String, Integer>();
        for (var local : Bodies.locals(callable)) {
            var name = local.getNameAsString();
            var ordinal = seen.merge(name, 1, Integer::sum) - 1;
            var id = add(elements, new Address.Local(owner, name, ordinal), Kind.LOCAL, name, owner,
                    Map.of(Fact.TYPE, local.getType().asString()));
            declarations.put(id, new Decl(unit, local));
        }
    }

    /**
     * The statements of a body, in order, and those each holds in its blocks, its own elements ({@link Bodies#nested}).
     * A statement has no name.
     */
    private void addStatements(List<Element> elements, Map<ElementId, Decl> declarations, Unit unit, CallableDeclaration<?> callable,
                               ElementId owner) {
        addStatements(elements, declarations, unit, Bodies.statements(callable), owner);
    }

    private void addStatements(List<Element> elements, Map<ElementId, Decl> declarations, Unit unit, List<Statement> statements,
                               ElementId owner) {
        for (var i = 0; i < statements.size(); i++) {
            var id = add(elements, new Address.Statement(owner, i), Kind.STATEMENT, "", owner, Map.of());
            declarations.put(id, new Decl(unit, statements.get(i)));
            addStatements(elements, declarations, unit, Bodies.nested(statements.get(i)), id);
        }
    }

    /** The local a declarator is, by its callable, its name, and which of that name it is; null if it is none. */
    public ElementId local(VariableDeclarator declarator) {
        var callable = declarator.findAncestor(CallableDeclaration.class).orElse(null);
        var ordinal = callable == null ? -1 : ordinal(callable, declarator);
        if (ordinal < 0) return null;
        var type = callable.findAncestor(TypeDeclaration.class).orElse(null);
        if (!(type instanceof TypeDeclaration<?> declared) || declared.getFullyQualifiedName().isEmpty()) return null;
        var owner = ids.get(new Address.Type(qualifiedOf(declared)));
        var method = owner == null ? null : ids.get(new Address.Callable(owner, nameOf(callable), keys.of(callable)));
        return method == null ? null : ids.get(new Address.Local(method, declarator.getNameAsString(), ordinal));
    }

    /**
     * Which of its name a local is among its method's: while a file's uses are bound, each method's are numbered once
     * (a generated one has thousands), and forgotten after, not to keep the file's tree.
     */
    private int ordinal(CallableDeclaration<?> callable, VariableDeclarator declarator) {
        if (ordinals == null) return Bodies.ordinal(Bodies.locals(callable), declarator);
        var numbered = ordinals.computeIfAbsent(callable, c -> {
            var seen = new HashMap<String, Integer>();
            var each = new IdentityHashMap<VariableDeclarator, Integer>();
            for (var local : Bodies.locals(c)) each.put(local, seen.merge(local.getNameAsString(), 1, Integer::sum) - 1);
            return each;
        });
        return numbered.getOrDefault(declarator, -1);
    }

    private IdentityHashMap<CallableDeclaration<?>, Map<VariableDeclarator, Integer>> ordinals; // while a file is bound

    /** The parameter of a method or constructor a declaration is (not a lambda's), or null. */
    public ElementId param(com.github.javaparser.ast.body.Parameter parameter) {
        var callable = parameter.getParentNode().filter(CallableDeclaration.class::isInstance).map(CallableDeclaration.class::cast).orElse(null);
        if (callable == null) return null;
        var type = callable.findAncestor(TypeDeclaration.class).orElse(null);
        if (!(type instanceof TypeDeclaration<?> declared) || declared.getFullyQualifiedName().isEmpty()) return null;
        var owner = ids.get(new Address.Type(qualifiedOf(declared)));
        var method = owner == null ? null : ids.get(new Address.Callable(owner, nameOf(callable), keys.of(callable)));
        return method == null ? null : ids.get(new Address.Param(method, parameter.getNameAsString()));
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
        } catch (RuntimeException | StackOverflowError unresolved) {
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
        } catch (RuntimeException | StackOverflowError unresolved) {
            return null;
        }
    }

    /**
     * The innermost element containing the node: a parameter (its declared type), a method or field, else its type.
     * A type declared inside a method is no element: what is inside it is inside the method.
     */
    public ElementId siteOf(Node node) {
        return placeOf(node).member();
    }

    /** Where a use is made: the member, parameter or type that makes it, and the statement of a body it is in, if any. */
    public record Place(ElementId member, ElementId statement) {
    }

    public Place placeOf(Node node) {
        var type = node.findAncestor(TypeDeclaration.class, t -> t.getFullyQualifiedName().isPresent()).orElseThrow();
        var owner = ids.get(new Address.Type(qualifiedOf(type)));
        var method = node.findAncestor(CallableDeclaration.class, c -> c.findAncestor(TypeDeclaration.class).orElse(null) == type);
        if (method.isPresent()) {
            var callable = ids.get(new Address.Callable(owner, nameOf(method.get()), keys.of(method.get())));
            var param = node.findAncestor(Parameter.class).filter(p -> p.getParentNode().orElse(null) == method.get());
            if (param.isPresent()) return new Place(ids.get(new Address.Param(callable, param.get().getNameAsString())), null);
            return new Place(callable, callable == null ? null : statementOf(node, method.get(), callable));
        }
        var field = node.findAncestor(VariableDeclarator.class).filter(v -> v.getParentNode()
                .filter(FieldDeclaration.class::isInstance).isPresent());
        return new Place(field.map(v -> ids.get(new Address.Field(owner, v.getNameAsString()))).orElse(owner), null);
    }

    /** The innermost statement of a callable's body a node is in, or null: in its signature, or in no statement of it. */
    private ElementId statementOf(Node node, CallableDeclaration<?> method, ElementId callable) {
        var above = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()); // the node and what holds it
        for (Node at = node; at != null && at != method; at = at.getParentNode().orElse(null)) above.add(at);
        List<Statement> statements = Bodies.statements(method);
        ElementId statement = null;
        while (true) {
            var place = placeAmong(statements, node, above);
            if (place < 0) return statement;
            var held = ids.get(new Address.Statement(statement == null ? callable : statement, place));
            if (held == null) return statement;
            statement = held;
            statements = Bodies.nested(statements.get(place));
        }
    }

    /**
     * Which of statements in written order holds the node, or -1: the last one starting at or before it, if it holds
     * it. A generated method may have thousands, and a file hundreds of thousands of uses.
     */
    private static int placeAmong(List<Statement> statements, Node node, Set<Node> above) {
        var begin = node.getBegin().orElseThrow();
        int low = 0, high = statements.size() - 1, found = -1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            if (statements.get(middle).getBegin().orElseThrow().isAfter(begin)) high = middle - 1;
            else {
                found = middle;
                low = middle + 1;
            }
        }
        return found >= 0 && above.contains(statements.get(found)) ? found : -1;
    }

    static String nameOf(CallableDeclaration<?> callable) {
        return callable instanceof ConstructorDeclaration ? "new" : callable.getNameAsString();
    }

    public static String qualifiedOf(TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElseThrow();
    }
}
