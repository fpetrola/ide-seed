package ide.java.text;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import ide.projection.PrimitiveRejected;

import java.util.Collection;
import java.util.IdentityHashMap;
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

    private final JavaParser parser;
    private final Consumer<Set<String>> changed;
    private final Map<String, Unit> units = new TreeMap<>();
    private final Map<CompilationUnit, Unit> byTree = new IdentityHashMap<>();

    /**
     * @param configuration how to parse (and resolve names in) the trees
     * @param changed       told after every change, with the paths of the files it added, replaced or removed
     */
    public Sources(ParserConfiguration configuration, Consumer<Set<String>> changed) {
        this.parser = new JavaParser(configuration);
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
        var unit = byTree.get(node.findCompilationUnit().orElseThrow());
        if (unit == null) throw new IllegalArgumentException("not in the sources: " + node);
        return unit;
    }

    public Collection<CompilationUnit> trees() {
        return units.values().stream().map(Unit::tree).toList();
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
        keep(new Unit(to, unit.text(), unit.tree()));
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

    /** Parses a text that is not one of the sources (an editor's copy, a text in the making). */
    public Optional<CompilationUnit> parseDetached(String text) {
        var result = parser.parse(text);
        return result.isSuccessful() ? result.getResult() : Optional.empty();
    }

    private Map<String, Unit> parse(Map<String, String> texts) {
        var parsed = new LinkedHashMap<String, Unit>();
        texts.forEach((path, text) -> {
            var result = parser.parse(text);
            if (!result.isSuccessful()) {
                throw new PrimitiveRejected("projection.unparsable", path + ": " + result.getProblems().getFirst().getVerboseMessage());
            }
            parsed.put(path, new Unit(path, text, withoutTokens(result.getResult().orElseThrow())));
        });
        return parsed;
    }

    /** Each node and comment keeps its range; the tokens, kept by any of them, would hold as much memory as the tree. */
    private static CompilationUnit withoutTokens(CompilationUnit tree) {
        tree.walk(Sources::forgetTokens);
        tree.getAllComments().forEach(Sources::forgetTokens);
        return tree;
    }

    private static void forgetTokens(Node node) {
        node.getRange().ifPresent(range -> node.setTokenRange(null).setRange(range));
    }

    private void keep(Unit unit) {
        forget(unit.path());
        units.put(unit.path(), unit);
        byTree.put(unit.tree(), unit);
    }

    private Unit forget(String path) {
        var unit = units.remove(path);
        if (unit != null) byTree.remove(unit.tree());
        return unit;
    }
}
