package ide.session;

import ide.core.CapabilityRegistry;
import ide.engine.Plan;
import ide.engine.Repairer;
import ide.engine.Result;
import ide.engine.Trace;
import ide.java.JavaProjection;
import ide.knowledge.Knowledge;
import ide.maven.Maven;
import ide.program.Element;
import ide.projection.Diagnostic;
import ide.projection.Location;
import ide.refactoring.Library;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The IDE over one Java project, as tests and adapters use it. It does nothing itself: each operation goes to the
 * part that does it, the session, the fixes, navigation, completion or the capabilities.
 */
public final class Workspace {

    private final JavaProjection project;
    private final Library library;
    private final Session session;
    private final Fixes fixes;
    private final Navigation navigation;
    private final Completion completion;
    private final Path root;
    private final Maven.Classpath classpath;

    private Workspace(JavaProjection project, Library library, Knowledge knowledge, Path root, Maven.Classpath classpath) {
        this.project = project;
        this.library = library;
        this.root = root;
        this.classpath = classpath;
        this.session = new Session(project, library, new Repairer(List.of(knowledge)));
        this.fixes = new Fixes(project, knowledge);
        this.navigation = new Navigation(project);
        this.completion = new Completion(project);
    }

    /** A workspace over these sources, with the standard library and knowledge; it has nowhere to save. */
    public static Workspace of(Map<String, String> sources) {
        var library = Library.standard();
        return new Workspace(JavaProjection.of(sources), library, Knowledge.standard(library), null,
                Maven.Classpath.none("sources in memory"));
    }

    /**
     * A workspace over the Java sources under {@code root}, whose libraries are what Maven says the project's are (asked
     * once, then remembered in {@code .ide/classpath.txt} while its POMs do not change): it logs to
     * {@code .ide/events.jsonl} there, and saves there.
     */
    public static Workspace open(Path root) {
        var library = Library.standard();
        var classpath = Maven.project(root).map(maven -> maven.classpath(root.resolve(".ide/classpath.txt")))
                .orElse(Maven.Classpath.none("no pom.xml in " + root.toAbsolutePath().normalize() + " or above it"));
        var workspace = new Workspace(JavaProjection.of(Session.sourcesIn(root), classpath.entries()), library,
                Knowledge.standard(library), root, classpath);
        workspace.logTo(root.resolve(".ide/events.jsonl"));
        workspace.session.opened(root, classpath.entries().size(), classpath.problem());
        return workspace;
    }

    /** The project's libraries, besides the JDK, and what went wrong asking for them, if anything. */
    public Maven.Classpath classpath() {
        return classpath;
    }

    // ---- the project

    public String source(String path) {
        return project.source(path);
    }

    public Map<String, String> sources() {
        return project.sources();
    }

    public Set<String> files() {
        return project.files();
    }

    public List<Diagnostic> diagnostics() {
        return project.diagnostics();
    }

    /** The problems the project would have if a file said {@code text}, without applying it. */
    public List<Diagnostic> check(String path, String text) {
        return project.diagnostics(path, text);
    }

    /** The compiler's errors: its diagnostics but the warnings. */
    public List<Diagnostic> errors() {
        return project.diagnostics().stream().filter(Diagnostic::isError).toList();
    }

    // ---- the library

    /** {@code "<level> <id>"} for every refactoring in the library. */
    public List<String> library() {
        return library.listing();
    }

    /** Adds the refactorings written in a {@code .ref} text; answers their ids. */
    public List<String> define(String scripts) {
        return session.define(scripts);
    }

    // ---- the session

    public Result invoke(String refactoring, Map<String, ?> args) {
        return session.invoke(refactoring, args);
    }

    public Session.Preview preview(String refactoring, Map<String, ?> args) {
        return session.preview(refactoring, args);
    }

    /** The innermost member, parameter or type a place of a file is inside: a place in a body is inside its method. */
    public Optional<Element> inside(String path, int line, int column) {
        return project.inside(path, line, column).map(id -> project.program().memberOf(id));
    }

    /** What a place of a file is inside, innermost first, as an editor widens a selection. */
    public List<JavaProjection.Span> around(String path, int line, int column) {
        return project.around(path, line, column);
    }

    public Optional<List<String>> options(String refactoring, String param, Map<String, ?> args) {
        return session.options(refactoring, param, args);
    }

    public Map<String, String> suggest(String refactoring, Map<String, ?> args) {
        return session.suggest(refactoring, args);
    }

    public List<String> expand(String refactoring, Map<String, ?> args) {
        return session.expand(refactoring, args);
    }

    public Result edit(String path, String text) {
        return session.edit(path, text);
    }

    public void undo() {
        session.undo();
    }

    public String diff() {
        return session.diff();
    }

    public void save(Path root) {
        session.save(root);
    }

    /** Writes the changed files where the workspace was opened from. */
    public void save() {
        if (root == null) throw new IllegalStateException("this workspace was not opened from a folder: nowhere to save");
        session.save(root);
    }

    public Workspace logTo(Path file) {
        session.logTo(file);
        return this;
    }

    public Trace whyRepair() {
        return session.whyRepair();
    }

    // ---- fixes

    public List<String> fixes(String path, int line) {
        return fixes.titles(path, line);
    }

    public List<String> asks(String path, int line, String title) {
        return fixes.asks(path, line, title);
    }

    public List<Plan.Ask> questions(String path, int line, String title) {
        return fixes.questions(path, line, title);
    }

    public Trace why(String path, int line) {
        return fixes.why(path, line);
    }

    public Result fix(String path, int line, String title) {
        return fix(path, line, title, Map.of());
    }

    public Result fix(String path, int line, String title, Map<String, String> answers) {
        return session.fix(fixes.find(path, line, title), answers);
    }

    // ---- reading

    public List<Completion.Candidate> completions(String path, String text, int offset) {
        return completion.options(path, text, offset);
    }

    public List<String> complete(String path, String text, int offset) {
        return completion.at(path, text, offset);
    }

    public List<String> model() {
        return navigation.model();
    }

    public Optional<Element> at(String path, int line, int column) {
        return navigation.at(path, line, column);
    }

    public Optional<Location> where(String element) {
        return navigation.where(element);
    }

    public List<Element> elements(Collection<String> kinds, Optional<String> path) {
        return navigation.elements(kinds, path);
    }

    public List<String> callers(String method) {
        return navigation.callers(method);
    }

    public List<String> hierarchy(String type) {
        return navigation.hierarchy(type);
    }

    public List<String> references(String element) {
        return navigation.references(element);
    }

    // ---- adapters

    /** Everything this workspace can do, as capabilities; built on each call, so it reflects the current library. */
    /** The folder the workspace was opened from, if it was. */
    public java.util.Optional<Path> root() {
        return java.util.Optional.ofNullable(root);
    }

    public CapabilityRegistry capabilities() {
        return Capabilities.of(this, library.all());
    }
}
