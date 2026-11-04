package ide.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.ast.expr.Expression;
import ide.java.primitives.Context;
import ide.java.primitives.JavaPrimitives;
import ide.java.semantics.Completion;
import ide.java.semantics.SaidBody;
import ide.java.semantics.EcjCheck;
import ide.java.semantics.Keys;
import ide.java.semantics.Positions;
import ide.java.semantics.Reader;
import ide.java.semantics.UseNeeds;
import ide.java.semantics.WorkspaceTypeSolver;
import ide.java.syntax.Decl;
import ide.java.text.Sources;
import ide.java.text.Unit;
import ide.program.Change;
import ide.program.Element;
import ide.program.ElementId;
import ide.program.Identity;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import ide.program.Reconciliation;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Location;
import ide.projection.Needs;
import ide.projection.Projection;

import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Java driver: Java sources in memory, read as a program and changed only through primitives. The layers below do
 * the work: {@code text} keeps the sources and edits them, {@code syntax} writes Java constructs as text,
 * {@code semantics} reads the program and resolves names, {@code primitives} has one class per primitive. This class
 * holds what they share: the sources, the identity, the current reading, and what every change makes stale.
 */
public final class JavaProjection implements Projection {

    /** Everything needed to come back: source text and identity. */
    public record Snapshot(Map<String, String> sources, Identity.State identity, Set<String> emptyPackages)
            implements Projection.Snapshot {
    }

    private static final JavaRules RULES = new JavaRules();

    private final Identity ids = new Identity();
    private final Keys keys = new Keys(ids);
    private final WorkspaceTypeSolver typeSolver;
    private final Reader reader;
    private final Sources sources;
    private final Context context;
    private final List<Path> classpath;
    private Set<String> emptyPackages = new TreeSet<>();
    private final Set<String> changed = new TreeSet<>(); // files changed since the last reading
    private boolean afresh = true; // read every file anew: at first, and after identity changed without the text
    private Reader.Reading reading;
    private List<Diagnostic> diagnostics;
    private final EcjCheck compiler;
    private final Map<Unit, List<Main>> mains = new java.util.WeakHashMap<>(); // what each file has, while it is the same

    private JavaProjection(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
        this.compiler = new EcjCheck(classpath);
        typeSolver = new WorkspaceTypeSolver(this::units, WorkspaceTypeSolver.libraries(classpath));
        reader = new Reader(ids, keys, typeSolver);
        sources = new Sources(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver)), this::invalidate);
        reader.compiledBy(paths -> compiler.bindings(sources.texts(), paths)); // what the compiler binds, then the solver
        reader.inTurn(sources::inTurn);
        context = new Context() {
            @Override
            public Program program() {
                return reading().program();
            }

            @Override
            public Decl declaration(Element element) {
                return reading().declaration(element.id());
            }

            @Override
            public Node use(String reference) {
                return reading().use(reference);
            }

            @Override
            public Sources sources() {
                return sources;
            }

            @Override
            public Identity ids() {
                return ids;
            }

            @Override
            public Keys keys() {
                return keys;
            }

            @Override
            public Reader reader() {
                return reader;
            }

            @Override
            public void declarePackage(String name) {
                emptyPackages.add(name);
                invalidate(Set.of());
            }
        };
    }

    public static JavaProjection of(Map<String, String> sources) {
        return of(sources, List.of());
    }

    /** A projection over these sources, whose libraries, besides the JDK, are the jars and class folders of a classpath. */
    public static JavaProjection of(Map<String, String> sources, List<Path> classpath) {
        var projection = new JavaProjection(classpath);
        projection.sources.put(sources);
        return projection;
    }

    public String source(String path) {
        return sources.unit(path).text();
    }

    public Set<String> files() {
        return Set.copyOf(sources.paths());
    }

    /** Path → text of every source file, sorted by path. */
    public Map<String, String> sources() {
        return sources.texts();
    }

    /** A class a program can be run from: its name, its file, the line it is declared at and its main method's. */
    public record Main(String type, String path, int line, int methodLine) {
    }

    /**
     * The classes a program can be run from, by file: the first top-level class of a file (the one Java launches from a
     * source file) with a main method Java runs: {@code static void main(String[])}, or, as Java 25 runs them, an
     * instance {@code main} or one with no arguments, not private.
     */
    public List<Main> mains() {
        var found = new ArrayList<Main>();
        for (var path : sources.paths().stream().sorted().toList()) {
            var file = sources.unit(path);
            found.addAll(mains.computeIfAbsent(file, JavaProjection::mainsOf)); // a unit is new when its text changes
        }
        return found;
    }

    /** The main a file has, if any: read from its DOM only when its text says main at all. */
    private static List<Main> mainsOf(Unit file) {
        if (!file.text().contains("main")) return List.of();
        var unit = file.dom();
        if (unit.types().isEmpty() || !(unit.types().getFirst() instanceof org.eclipse.jdt.core.dom.AbstractTypeDeclaration type)) return List.of();
        if (type instanceof org.eclipse.jdt.core.dom.TypeDeclaration declared && declared.isInterface()
                || type instanceof org.eclipse.jdt.core.dom.AnnotationTypeDeclaration) {
            return List.of();
        }
        for (var member : type.bodyDeclarations()) {
            if (member instanceof org.eclipse.jdt.core.dom.MethodDeclaration method && !method.isConstructor()
                    && method.getName().getIdentifier().equals("main") && method.getReturnType2() != null
                    && method.getReturnType2().isPrimitiveType() && method.getReturnType2().toString().equals("void")
                    && !org.eclipse.jdt.core.dom.Modifier.isPrivate(method.getModifiers()) && runsWith(method)) {
                var pkg = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName() + ".";
                return List.of(new Main(pkg + type.getName().getIdentifier(), file.path(),
                        unit.getLineNumber(type.getName().getStartPosition()), unit.getLineNumber(method.getName().getStartPosition())));
            }
        }
        return List.of();
    }

    private static boolean runsWith(org.eclipse.jdt.core.dom.MethodDeclaration method) {
        if (method.parameters().isEmpty()) return true;
        if (method.parameters().size() != 1) return false;
        var parameter = (org.eclipse.jdt.core.dom.SingleVariableDeclaration) method.parameters().getFirst();
        var type = parameter.getType().toString() + "[]".repeat(parameter.getExtraDimensions());
        return type.equals("String[]") || type.equals("java.lang.String[]")
                || parameter.isVarargs() && (type.equals("String") || type.equals("java.lang.String"));
    }

    /** A stretch of a file, from the first character to the last, each as line and column from 1. */
    public record Span(int line, int column, int endLine, int endColumn) {
    }

    /**
     * What a place of a file is inside, innermost first: each Java construct's stretch, once, as an editor widens a
     * selection step by step (a name, the expression, the statement, the block, the method, the class).
     */
    public List<Span> around(String path, int line, int column) {
        var at = new com.github.javaparser.Position(line, column);
        var spans = new java.util.LinkedHashSet<Span>();
        sources.unit(path).tree().walk(node -> node.getRange()
                .filter(range -> range.contains(new com.github.javaparser.Range(at, at)) || range.begin.equals(at))
                .ifPresent(range -> spans.add(new Span(range.begin.line, range.begin.column, range.end.line, range.end.column))));
        var starts = new ArrayList<Integer>(List.of(0));
        var text = sources.unit(path).text();
        for (var i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        java.util.function.ToIntFunction<Span> size = s -> starts.get(s.endLine() - 1) + s.endColumn() - starts.get(s.line() - 1) - s.column();
        return spans.stream().sorted(java.util.Comparator.comparingInt(size)).toList();
    }

    /** The whole stretch an element's declaration takes, from its modifiers to its end; nothing for what has no text. */
    public Optional<Span> declared(ElementId element) {
        return Positions.declared(reading(), element).map(range -> new Span(range.begin.line, range.begin.column, range.end.line, range.end.column));
    }

    @Override
    public Program program() {
        return reading().program();
    }

    /** Applies a primitive as text edits and answers what it changed on purpose. */
    @Override
    public Change apply(Primitive primitive) {
        return JavaPrimitives.apply(primitive, context);
    }

    /**
     * The user's text for a file; what it renamed, re-signed or moved is still the element it was, and so is a statement
     * it kept, by its order and text.
     */
    @Override
    public void edit(String path, String text) {
        var before = program();
        var said = said(path);
        if (text == null) sources.change(Map.of(), List.of(path));
        else sources.put(Map.of(path, text));
        reconcile(before);
        restate(before, said);
    }

    /** What each statement of a file says, as last read. */
    private Map<ElementId, String> said(String path) {
        var said = new java.util.HashMap<ElementId, String>();
        reading().declarationsIn(path).forEach((id, declaration) -> {
            if (program().element(id).kind() == Kind.STATEMENT) said.put(id, text(declaration));
        });
        return said;
    }

    private static String text(Decl declaration) {
        return declaration.unit().textBetween(declaration.range().begin, declaration.range().end).replaceAll("\\s+", " ");
    }

    /**
     * The statements of each body the edit touched are paired with what they were by their order and text; what is
     * left is new, which is safe: the verifier sees it as added.
     */
    private void restate(Program before, Map<ElementId, String> said) {
        var program = program();
        var placed = false;
        for (var callable : said.keySet().stream().map(id -> before.element(id).parent()).distinct().toList()) {
            if (!program.contains(callable)) continue;
            var olds = before.children(callable).stream().filter(e -> e.kind() == Kind.STATEMENT).map(Element::id).toList();
            var news = program.children(callable).stream().filter(e -> e.kind() == Kind.STATEMENT).map(Element::id).toList();
            var paired = Reconciliation.statements(olds, olds.stream().map(said::get).toList(),
                    news.stream().map(id -> text(reading().declaration(id))).toList());
            if (paired.equals(news)) continue;
            var unpaired = new java.util.ArrayList<ElementId>();
            for (var id : olds) if (!paired.contains(id)) unpaired.add(id);
            for (var id : news) if (!paired.contains(id)) unpaired.add(id);
            ids.forget(unpaired);
            ids.placeStatements(callable, paired);
            placed = true;
        }
        if (placed) {
            afresh = true; // a free place is read with a new id
            invalidate(Set.of());
        }
    }

    /**
     * Pairs, round by round, what the edit renamed, re-signed or moved with what it was (types first, then what they
     * hold, then parameters), reading again after each round. What the edit removed is forgotten, so that its id never
     * comes back for something else.
     */
    private void reconcile(Program before) {
        for (var round = 0; round < 3; round++) {
            var pairs = Reconciliation.pairs(before, program());
            if (pairs.isEmpty()) break;
            pairs.forEach(ids::transfer);
            afresh = true; // what was read under the new ids, in any file, names ids that are gone
            invalidate(Set.of());
        }
        var after = program();
        var removed = new java.util.ArrayList<ElementId>();
        for (var element : before.elements()) {
            if (!element.inLibrary() && !after.contains(element.id())) removed.add(element.id());
        }
        ids.forget(removed); // in one pass: one by one, each went over every address
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(sources.texts(), ids.save(), Set.copyOf(emptyPackages));
    }

    @Override
    public void restore(Projection.Snapshot saved) {
        var snapshot = (Snapshot) saved;
        var same = ids.is(snapshot.identity()); // a change refused before it changed any id, as a text that does not parse
        sources.restore(snapshot.sources()); // the files whose text differs are read again
        ids.restore(snapshot.identity());
        emptyPackages = new TreeSet<>(snapshot.emptyPackages());
        if (!same) afresh = true; // ids came back: what was read names ids that are not those
        invalidate(Set.of());
    }

    @Override
    public LanguageRules rules() {
        return RULES;
    }

    @Override
    public List<Diagnostic> diagnostics() {
        if (diagnostics == null) diagnostics = compiler.check(sources.texts());
        return diagnostics;
    }

    /**
     * The problems the sources would have if a file said {@code text}, as Eclipse's reconciler finds them while one
     * types: compiled as the rest is, the program untouched; syntax errors too.
     */
    public List<Diagnostic> diagnostics(String path, String text) {
        var texts = new java.util.HashMap<>(sources.texts());
        texts.put(path, text);
        var found = compiler.check(texts);
        diagnostics = null; // the compiler remembers the edited text now: the program's is compiled again when asked
        return found;
    }

    @Override
    public Optional<CompletionContext> completionContext(String path, String text, int offset) {
        program(); // the identities of the saved version, which the edited text is matched against
        return Completion.at(sources, reader, ids, text, offset);
    }

    @Override
    public Optional<Location> locate(ElementId element) {
        return Positions.of(reading(), element);
    }

    @Override
    public Optional<ElementId> elementAt(String path, int line, int column) {
        return Positions.at(reading(), sources, path, line, column);
    }

    @Override
    public Optional<ElementId> inside(String path, int line, int column) {
        return Positions.inside(reading(), path, line, column);
    }

    /** What a field or a call that binds nothing needs; nothing is known of a written type's. */
    @Override
    public Optional<Needs> needs(String use) {
        var reading = reading();
        if (reading.program().unresolved(use).isEmpty() || !(reading.use(use) instanceof Expression value)) return Optional.empty();
        return Optional.of(UseNeeds.of(value));
    }

    @Override
    public Optional<String> ordered(ElementId statement, ElementId other) {
        var program = program();
        for (var id : List.of(statement, other)) {
            if (!program.contains(id) || program.element(id).kind() != ide.program.Kind.STATEMENT) return Optional.of(id.value() + " is no statement");
        }
        return ide.java.primitives.JavaPrimitives.ordered(statement, other, context);
    }

    @Override
    public Optional<String> said(ElementId element) {
        return SaidBody.of(reading(), element);
    }

    /**
     * The files whose uses the last reading resolved: every file at first, after a type changed, or after identity
     * changed without the text; otherwise the files a change touched and the files that name what it changed.
     */
    public Set<String> resolvedLastTime() {
        return reading().resolved();
    }

    /** The program as last read, with where each element is declared and the node of each use. */
    private Reader.Reading reading() {
        if (reading == null) {
            reading = reader.read(sources.units(), emptyPackages, afresh ? null : Set.copyOf(changed));
            changed.clear();
            afresh = false;
            forgetResolved(); // what resolving cached holds nodes of other files: their trees could not be let go
        }
        return reading;
    }

    private Collection<Unit> units() {
        return sources.units();
    }

    /** After any change: what was resolved before describes another program, and the files at {@code paths} changed. */
    private void invalidate(Set<String> paths) {
        changed.addAll(paths);
        forgetResolved(); // resolution caches point into replaced trees
        typeSolver.reset();
        reading = null;
        diagnostics = null;
    }

    private void forgetResolved() {
        JavaParserFacade.clearInstances();
        sources.trees().forEach(JavaProjection::forgetResolvedTypes);
    }

    /**
     * The facade also caches resolved types on the nodes themselves; in units that were not parsed again they would
     * still describe the program before the change (e.g. a field whose declared type changed elsewhere).
     */
    private static void forgetResolvedTypes(CompilationUnit cu) {
        cu.walk(node -> {
            for (var key : List.copyOf(node.getDataKeys())) {
                if (key.getClass().getName().startsWith(JavaParserFacade.class.getName() + "$")) node.removeData(key);
            }
        });
    }
}
