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
    private final Plans plans;
    private final Attention attention;
    private final Conversation conversation;
    private final RunConfigs runs;

    private Workspace(JavaProjection project, Library library, Knowledge knowledge, Path root, Maven.Classpath classpath) {
        this.project = project;
        this.library = library;
        this.root = root;
        this.classpath = classpath;
        this.session = new Session(project, library, new Repairer(List.of(knowledge)));
        this.fixes = new Fixes(project, knowledge);
        this.navigation = new Navigation(project);
        this.completion = new Completion(project);
        this.plans = new Plans(root == null ? null : root.resolve(".ide/plans.json"), session::event, java.time.Clock.systemUTC());
        this.attention = new Attention(session::event);
        this.runs = new RunConfigs(root == null ? null : root.resolve(".ide/runs.json"), session::event);
        this.conversation = new Conversation(root == null ? null : root.resolve(".ide/conversation.json"), session::event);
        if (root != null) session.historyIn(root.resolve(".ide/history.jsonl"));
        session.attribution(() -> plans.doing().apply(java.time.Instant.now()).orElse(""), this::places);
    }

    /**
     * The places of the code a refactoring's arguments name: each element's whole declaration (a statement's lines), and the lines it is
     * given (as {@code 31-40} or {@code 46:93-46:111}) in the file of the first element given.
     */
    private List<History.Place> places(Map<String, ?> args) {
        var places = new java.util.ArrayList<History.Place>();
        for (var value : args.values()) {
            var items = value instanceof Collection<?> list ? list : List.of(String.valueOf(value));
            for (var item : items) {
                var text = String.valueOf(item);
                if (project.program().find(text).isEmpty() && !project.program().contains(new ide.program.ElementId(text))) continue;
                var element = project.program().find(text).orElseGet(() -> project.program().element(new ide.program.ElementId(text)));
                try { // its whole declaration, in the file of its top-level type: a statement's lines too
                    var file = project.program().topLevel(element.id()).fact(ide.program.Fact.FILE);
                    var whole = project.declared(element.id());
                    if (file != null && whole.isPresent()) {
                        places.add(new History.Place(file, whole.get().line(), whole.get().endLine(), element.qualified()));
                    }
                } catch (RuntimeException notInAFile) {
                    // a package, or what no file declares
                }
            }
        }
        var lines = args.get("lines");
        if (lines != null && !places.isEmpty()) {
            var spec = java.util.regex.Pattern.compile("(\\d+)(?::\\d+)?(?:-(\\d+)(?::\\d+)?)?").matcher(String.valueOf(lines).strip());
            if (spec.matches()) {
                var from = Integer.parseInt(spec.group(1));
                var to = spec.group(2) == null ? from : Integer.parseInt(spec.group(2));
                var file = places.getFirst().path();
                // the lines are what it takes: the member they are in is only where
                places.removeIf(p -> p.path().equals(file) && p.line() <= from && p.endLine() >= to);
                places.addFirst(new History.Place(file, from, to, ""));
            }
        }
        return places;
    }

    /** The steps of a plan's tasks (all, with none), to see again. */
    public List<Map<String, Object>> replay(Optional<String> plan) {
        return session.history().steps(task -> plan.isEmpty() || plans.planOf(task).filter(plan.get()::equals).isPresent());
    }

    /** A step with what it changed in each file. */
    public Map<String, Object> replayStep(int n) {
        return session.history().step(n);
    }

    /** Brings the files back to how step {@code n} left them; the steps after it are discarded, and nothing is undone. */
    public void continueFrom(int n) {
        session.back(n);
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

    /** The plans being followed, kept with the project. */
    Plans plans() {
        return plans;
    }

    /**
     * Points the person at an element, its whole declaration, or at lines {@code line..end} of a file, with a note:
     * refused if there is no such element, file or line.
     */
    Attention.Pointed point(Optional<String> element, Optional<String> path, int line, int end, String note) {
        var place = place(element, path, line, end);
        return attention.point(place.path(), place.line(), place.endLine(), place.element(), note);
    }

    /** Links a place of the code to a task: an element (its whole declaration) or lines of a file, with a note. */
    Map<String, Object> link(String task, Optional<String> element, Optional<String> path, int line, int end, String note) {
        var place = place(element, path, line, end);
        return plans.link(task, new Plans.Link(place.path(), place.line(), place.endLine(), place.element(), note));
    }

    /** A place of the code: an element's whole declaration, or lines of a file; refused if there is no such thing. */
    private Plans.Link place(Optional<String> element, Optional<String> path, int line, int end) {
        if (element.isPresent()) {
            var at = where(element.get()).orElseThrow(() -> new IllegalArgumentException("no element " + element.get() + " is declared in a file"));
            var whole = project.declared(project.program().find(element.get()).orElseThrow().id());
            return new Plans.Link(at.path(), whole.map(JavaProjection.Span::line).orElse(at.line()),
                    whole.map(JavaProjection.Span::endLine).orElse(at.line()), element.get(), "");
        }
        var file = path.orElseThrow(() -> new IllegalArgumentException("say an element, or a path and a line"));
        if (!files().contains(file)) throw new IllegalArgumentException("no file " + file);
        var lines = source(file).lines().count();
        if (line < 1 || line > lines || end > lines) {
            throw new IllegalArgumentException("no line " + (line < 1 || line > lines ? line : end) + " in " + file + ": it has " + lines);
        }
        return new Plans.Link(file, line, Math.max(line, end), "", "");
    }

    /** The classes a program can be run from, of a file if one is said. */
    List<JavaProjection.Main> mains(Optional<String> path) {
        return project.mains().stream().filter(main -> path.isEmpty() || main.path().equals(path.get())).toList();
    }

    RunConfigs runs() {
        return runs;
    }

    /** Keeps a run configuration, refused if its class has no main to run. */
    RunConfigs.Config runConfig(RunConfigs.Config config) {
        main(config.type());
        return runs.set(config);
    }

    private JavaProjection.Main main(String type) {
        return project.mains().stream().filter(m -> m.type().equals(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no main to run in " + type + ": it is not a class with a main method"));
    }

    /**
     * How to run a program, as it is on disk: by a run configuration, the one of a class (made if it has none), or the
     * chosen one. The Java the IDE runs on runs it from its sources (the class's file, and the others of the project as
     * it uses them), with the configuration's options, the project's libraries and the program's arguments, in its
     * folder (the project's, Maven's if it has one, unless the configuration says another) with its environment.
     */
    Map<String, Object> runCommand(Optional<String> name, Optional<String> type) {
        var config = name.map(runs::get).or(() -> type.map(runs::of)).or(runs::chosen)
                .orElseThrow(() -> new IllegalArgumentException("say what to run: a class with a main, or a run configuration"));
        runs.choose(config.name());
        var main = main(config.type());
        var folder = root().orElseThrow(() -> new IllegalStateException("this workspace was not opened from a folder: nothing to run"));
        var launcher = ProcessHandle.current().info().command().filter(c -> Path.of(c).getFileName().toString().startsWith("java"))
                .orElse("java");
        var command = new java.util.ArrayList<String>(List.of(launcher));
        command.addAll(config.options());
        if (!classpath.entries().isEmpty()) {
            command.add("-cp");
            command.add(String.join(java.io.File.pathSeparator, classpath.entries().stream().map(Path::toString).toList()));
        }
        command.add(folder.resolve(main.path()).toString());
        command.addAll(config.arguments());
        var project = ide.maven.Maven.project(folder).map(ide.maven.Maven::folder).orElse(folder);
        var shown = new java.util.LinkedHashMap<String, Object>();
        shown.put("title", config.name());
        shown.put("config", config.name());
        shown.put("type", config.type());
        shown.put("command", command);
        shown.put("directory", config.directory().isBlank() ? project.toString() : project.resolve(config.directory()).normalize().toString());
        shown.put("environment", config.variables());
        return shown;
    }

    private Transcript transcript;

    /** What was said while a task was being done, read from the agent's conversation, if it is known. */
    List<Map<String, Object>> talk(String task) {
        plans.task(Optional.of(task)); // refused if there is no such task
        var written = conversation.transcript();
        if (written.isEmpty()) return List.of();
        if (transcript == null || !transcript.file().equals(Path.of(written))) {
            transcript = new Transcript(Path.of(written), root().map(r -> r.toAbsolutePath().normalize().toString()).orElse(""));
        }
        var doing = plans.doing();
        return transcript.said().stream().filter(said -> doing.apply(said.at()).filter(task::equals).isPresent()).map(said -> {
            var shown = new java.util.LinkedHashMap<String, Object>();
            shown.put("at", said.at().toString());
            shown.put("who", said.who());
            shown.put("kind", said.kind());
            shown.put("text", said.text());
            return (Map<String, Object>) shown;
        }).toList();
    }

    /** The agent's conversation on this project. */
    Conversation conversation() {
        return conversation;
    }

    /** Where attention is, both ways. */
    Attention attention() {
        return attention;
    }

    /** Takes what changed on disk where the workspace was opened from, as edits by hand. */
    public Session.Sync sync() {
        if (root == null) throw new IllegalStateException("this workspace was not opened from a folder: no disk to follow");
        return session.sync(root);
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

    public List<Map<String, String>> uses(String element) {
        return navigation.uses(element);
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
