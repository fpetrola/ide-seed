package ide.java.text;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import ide.projection.PrimitiveRejected;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.TreeSet;
import java.util.Set;

/**
 * The source files of a workspace. They change only as text: whole files put or removed, or an {@link EditSet}.
 * Every change is all or nothing (a text that does not parse is refused and nothing changes), only the units it
 * touches are parsed again, and it is announced once. The only place in the driver that parses.
 */
public final class Sources {

    private final ParserConfiguration configuration;
    private final Trees trees;
    private final Consumer<Set<String>> changed;
    private final Map<String, Unit> units = new TreeMap<>();

    /**
     * @param configuration how to parse (and resolve names in) the trees
     * @param changed       told after every change, with the paths of the files it added, replaced or removed
     */
    public Sources(ParserConfiguration configuration, Consumer<Set<String>> changed) {
        this(configuration, changed, 1 << 20);
    }

    /**
     * @param keeping how much source text the trees used last may have to stay in memory though nothing holds them: a
     *                tree takes tens of times its text
     */
    public Sources(ParserConfiguration configuration, Consumer<Set<String>> changed, int keeping) {
        this.configuration = configuration;
        this.trees = new Trees(configuration, keeping);
        this.changed = changed;
    }

    /** The paths of the files, sorted. */
    public List<String> paths() {
        return List.copyOf(units.keySet());
    }

    public boolean has(String path) {
        return units.containsKey(path);
    }

    public Unit unit(String path) {
        var unit = units.get(path);
        if (unit == null) throw new IllegalArgumentException("no source " + path);
        return unit;
    }

    /** The units, sorted by path. */
    public Collection<Unit> units() {
        return List.copyOf(units.values());
    }

    /** The unit a node of one of the trees belongs to. */
    public Unit unitOf(Node node) {
        var unit = Unit.of(node.findCompilationUnit().orElseThrow());
        if (unit == null || units.get(unit.path()) != unit) throw new IllegalArgumentException("not in the sources: " + node);
        return unit;
    }

    /** The trees in memory, the only ones anything was cached in. */
    public Collection<CompilationUnit> trees() {
        return units.values().stream().flatMap(unit -> unit.parsed().stream()).toList();
    }

    /** Path → text of every file, sorted by path. */
    public Map<String, String> texts() {
        var texts = new TreeMap<String, String>();
        units.forEach((path, unit) -> texts.put(path, unit.text()));
        return texts;
    }

    /** Adds or replaces files. */
    public void put(Map<String, String> texts) {
        change(texts, List.of());
    }

    /** Adds or replaces some files and removes others, at once. */
    public void change(Map<String, String> texts, Collection<String> removed) {
        var parsed = parse(texts);
        removed.forEach(this::forget);
        parsed.values().forEach(this::keep);
        var paths = new TreeSet<>(texts.keySet());
        paths.addAll(removed);
        changed.accept(paths);
    }

    public void apply(EditSet edits) {
        if (edits.isEmpty()) return;
        var parsed = parse(edits.texts());
        parsed.values().forEach(this::keep);
        changed.accept(parsed.keySet());
    }

    /** A file moves to another path; its text and tree stay. */
    public void rename(String from, String to) {
        var unit = forget(from);
        keep(new Unit(to, unit.text(), unit.dom(), trees));
        changed.accept(Set.of(from, to));
    }

    /** Back to exactly these files; one whose text is the same keeps its tree. */
    public void restore(Map<String, String> texts) {
        var differ = new TreeMap<String, String>();
        texts.forEach((path, text) -> {
            if (!units.containsKey(path) || !units.get(path).text().equals(text)) differ.put(path, text);
        });
        var gone = new TreeSet<>(units.keySet());
        gone.removeAll(texts.keySet());
        var parsed = parse(differ);
        gone.forEach(this::forget);
        parsed.values().forEach(this::keep);
        var paths = new TreeSet<>(differ.keySet());
        paths.addAll(gone);
        changed.accept(paths);
    }

    /** A unit for a text in the making: parsed like the sources, but not one of them. */
    public Unit draft(String path, String text) {
        return parse(Map.of(path, text)).get(path);
    }

    /**
     * Each unit in turn, the trees of the next ones (those {@code parsed} asks for) parsed meanwhile on other threads, a
     * few at a time: a project read whole is parsed again for each pass over it, and a tree may be tens of megabytes.
     */
    public void inTurn(List<Unit> units, java.util.function.Predicate<Unit> parsed, Consumer<Unit> each) {
        var ahead = new java.util.ArrayDeque<java.util.concurrent.Future<CompilationUnit>>();
        var next = 0;
        for (var unit : units) {
            while (next < units.size() && ahead.size() < AHEAD) {
                var coming = units.get(next++);
                ahead.add(parsed.test(coming) && coming.parsed().isEmpty()
                        ? PARSING.submit(() -> trees.parse(coming.text()).getResult().orElse(null))
                        : java.util.concurrent.CompletableFuture.completedFuture(null));
            }
            CompilationUnit tree = null;
            try {
                tree = ahead.poll().get();
                if (tree != null) unit.adopt(tree);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException failed) {
                // parsed again when asked for
            }
            each.accept(unit);
            java.lang.ref.Reference.reachabilityFence(tree); // a tree bigger than what is kept lives through its turn
        }
    }

    private static org.eclipse.jdt.core.dom.CompilationUnit done(java.util.concurrent.Future<org.eclipse.jdt.core.dom.CompilationUnit> parsing) {
        try {
            return parsing.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while parsing", interrupted);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw new IllegalStateException(failed.getCause());
        }
    }

    private static final int AHEAD = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));

    private static final java.util.concurrent.ExecutorService PARSING = java.util.concurrent.Executors.newFixedThreadPool(AHEAD, work -> {
        var thread = new Thread(work, "parsing ahead");
        thread.setDaemon(true);
        return thread;
    });

    /** Parses a text that is not one of the sources (an editor's copy, a text in the making). */
    public Optional<CompilationUnit> parseDetached(String text) {
        var result = new JavaParser(configuration).parse(text);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }

    private Map<String, Unit> parse(Map<String, String> texts) {
        var parsed = new LinkedHashMap<String, Unit>();
        var doms = new java.util.HashMap<String, java.util.concurrent.Future<org.eclipse.jdt.core.dom.CompilationUnit>>();
        if (texts.size() > 1) texts.forEach((path, text) -> doms.put(path, PARSING.submit(() -> Trees.dom(text)))); // a project, on every core
        texts.forEach((path, text) -> {
            var dom = doms.containsKey(path) ? done(doms.get(path)) : Trees.dom(text); // the compiler's parser says whether it parses
            for (var problem : dom.getProblems()) {
                if (problem.isError() && (problem.getID() & org.eclipse.jdt.core.compiler.IProblem.Syntax) != 0) {
                    throw new PrimitiveRejected("projection.unparsable", path + ": line " + problem.getSourceLineNumber() + ": " + problem.getMessage());
                }
            }
            parsed.put(path, new Unit(path, text, dom, trees));
        });
        return parsed;
    }

    private void keep(Unit unit) {
        forget(unit.path());
        units.put(unit.path(), unit);
    }

    private Unit forget(String path) {
        var unit = units.remove(path);
        if (unit != null) trees.drop(unit);
        return unit;
    }
}
