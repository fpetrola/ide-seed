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

    private JavaProjection(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
        this.compiler = new EcjCheck(classpath);
        typeSolver = new WorkspaceTypeSolver(this::trees, WorkspaceTypeSolver.libraries(classpath));
        reader = new Reader(ids, keys, typeSolver);
        sources = new Sources(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver)), this::invalidate);
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
        sources.put(Map.of(path, text));
        reconcile(before);
        restate(before, said);
    }

    /** What each statement of a file says, as last read. */
    private Map<ElementId, String> said(String path) {
        var said = new java.util.HashMap<ElementId, String>();
        reading().declarations().forEach((id, declaration) -> {
            if (declaration.unit().path().equals(path) && program().element(id).kind() == Kind.STATEMENT) said.put(id, text(declaration));
        });
        return said;
    }

    private static String text(Decl declaration) {
        return declaration.unit().textOf(declaration.node()).replaceAll("\\s+", " ");
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
                    news.stream().map(id -> text(reading().declarations().get(id))).toList());
            if (paired.equals(news)) continue;
            for (var id : olds) if (!paired.contains(id)) ids.forget(id);
            for (var id : news) if (!paired.contains(id)) ids.forget(id);
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
        for (var element : before.elements()) {
            if (!element.inLibrary() && !after.contains(element.id())) ids.forget(element.id());
        }
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(sources.texts(), ids.save(), Set.copyOf(emptyPackages));
    }

    @Override
    public void restore(Projection.Snapshot saved) {
        var snapshot = (Snapshot) saved;
        sources.restore(snapshot.sources());
        ids.restore(snapshot.identity());
        emptyPackages = new TreeSet<>(snapshot.emptyPackages());
        afresh = true;
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
        }
        return reading;
    }

    private Collection<CompilationUnit> trees() {
        return sources.trees();
    }

    /** After any change: what was resolved before describes another program, and the files at {@code paths} changed. */
    private void invalidate(Set<String> paths) {
        changed.addAll(paths);
        JavaParserFacade.clearInstances(); // resolution caches point into replaced trees
        sources.trees().forEach(JavaProjection::forgetResolvedTypes);
        typeSolver.reset();
        reading = null;
        diagnostics = null;
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
