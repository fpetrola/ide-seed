package ide.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import ide.java.primitives.Context;
import ide.java.primitives.JavaPrimitives;
import ide.java.semantics.Completion;
import ide.java.semantics.JavacCheck;
import ide.java.semantics.Keys;
import ide.java.semantics.Reader;
import ide.java.semantics.WorkspaceTypeSolver;
import ide.java.syntax.Decl;
import ide.java.text.Sources;
import ide.program.Change;
import ide.program.Element;
import ide.program.Identity;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.CompletionContext;
import ide.projection.Diagnostic;
import ide.projection.LanguageRules;
import ide.projection.Projection;

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
    private Set<String> emptyPackages = new TreeSet<>();
    private Reader.Reading reading;
    private List<Diagnostic> diagnostics;

    private JavaProjection() {
        typeSolver = new WorkspaceTypeSolver(this::trees, new ReflectionTypeSolver());
        reader = new Reader(ids, keys, typeSolver);
        sources = new Sources(new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(typeSolver)), this::invalidate);
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
                invalidate();
            }
        };
    }

    public static JavaProjection of(Map<String, String> sources) {
        var projection = new JavaProjection();
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

    @Override
    public void edit(String path, String text) {
        sources.put(Map.of(path, text));
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
        invalidate();
    }

    @Override
    public LanguageRules rules() {
        return RULES;
    }

    @Override
    public List<Diagnostic> diagnostics() {
        if (diagnostics == null) diagnostics = JavacCheck.errors(sources.texts());
        return diagnostics;
    }

    @Override
    public Optional<CompletionContext> completionContext(String path, String text, int offset) {
        program(); // the identities of the saved version, which the edited text is matched against
        return Completion.at(sources, reader, ids, text, offset);
    }

    /** The program as last read, with where each element is declared and the node of each use. */
    private Reader.Reading reading() {
        if (reading == null) reading = reader.read(sources.units(), emptyPackages);
        return reading;
    }

    private Collection<CompilationUnit> trees() {
        return sources.trees();
    }

    /** After any change: what was read or resolved before describes another program. */
    private void invalidate() {
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
