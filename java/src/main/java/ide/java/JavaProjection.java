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
import ide.java.semantics.JavacCheck;
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

    private JavaProjection(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
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

    @Override
    public Program program() {
        return reading().program();
    }

    /** Applies a primitive as text edits and answers what it changed on purpose. */
    @Override
    public Change apply(Primitive primitive) {
        return JavaPrimitives.apply(primitive, context);
    }

    /** The user's text for a file; what it renamed, re-signed or moved is still the element it was. */
    @Override
    public void edit(String path, String text) {
        var before = program();
        sources.put(Map.of(path, text));
        reconcile(before);
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
        if (diagnostics == null) diagnostics = JavacCheck.errors(sources.texts(), classpath);
        return diagnostics;
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

    /** What a field or a call that binds nothing needs; nothing is known of a written type's. */
    @Override
    public Optional<Needs> needs(String use) {
        var reading = reading();
        if (reading.program().unresolved(use).isEmpty() || !(reading.use(use) instanceof Expression value)) return Optional.empty();
        return Optional.of(UseNeeds.of(value));
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
