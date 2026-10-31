package ide.session;

import ide.core.CapabilityRegistry;
import ide.engine.Repairer;
import ide.engine.Result;
import ide.engine.Trace;
import ide.java.JavaProjection;
import ide.knowledge.Knowledge;
import ide.projection.Diagnostic;
import ide.refactoring.Library;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private Workspace(JavaProjection project, Library library, Knowledge knowledge) {
        this.project = project;
        this.library = library;
        this.session = new Session(project, library, new Repairer(List.of(knowledge)));
        this.fixes = new Fixes(project, knowledge);
        this.navigation = new Navigation(project);
        this.completion = new Completion(project);
    }

    /** A workspace over these sources, with the standard library and knowledge. */
    public static Workspace of(Map<String, String> sources) {
        var library = Library.standard();
        return new Workspace(JavaProjection.of(sources), library, Knowledge.standard(library));
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

    public List<String> complete(String path, String text, int offset) {
        return completion.at(path, text, offset);
    }

    public List<String> model() {
        return navigation.model();
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
    public CapabilityRegistry capabilities() {
        return Capabilities.of(this, library.all());
    }
}
