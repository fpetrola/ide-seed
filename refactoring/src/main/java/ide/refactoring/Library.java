package ide.refactoring;

import ide.refactoring.script.ScriptParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The refactorings that can be called by id: the primitives, the composites written in Java, scripts, and intents,
 * which its planner makes hold.
 */
public final class Library {

    private final Map<String, Refactoring> refactorings = new TreeMap<>();
    private Planner planner;

    /** The primitives, change-signature, and the standard catalog. */
    public static Library standard() {
        var library = new Library();
        Primitives.all().forEach(library::add);
        library.add(new ChangeSignature());
        library.define(catalog());
        return library;
    }

    public Library add(Refactoring refactoring) {
        refactorings.put(refactoring.id(), refactoring);
        return this;
    }

    /** Adds the refactorings written in a {@code .ref} text; answers their ids. */
    public List<String> define(String scripts) {
        var defined = ScriptParser.parse(scripts);
        defined.forEach(this::add);
        return defined.stream().map(Refactoring::id).toList();
    }

    /** Who makes its intents hold. */
    public Library planner(Planner planner) {
        this.planner = planner;
        return this;
    }

    /** Who makes its intents hold, or null if nobody does. */
    Planner planner() {
        return planner;
    }

    public Refactoring get(String id) {
        var refactoring = refactorings.get(id);
        if (refactoring == null) throw new IllegalArgumentException("unknown refactoring: " + id);
        return refactoring;
    }

    /** Every refactoring, by id. */
    public Collection<Refactoring> all() {
        return List.copyOf(refactorings.values());
    }

    /** {@code "<level> <id>"} for every refactoring. */
    public List<String> listing() {
        return refactorings.values().stream().map(r -> r.level() + " " + r.id()).toList();
    }

    private static String catalog() {
        try (var in = Library.class.getResourceAsStream("/ide/refactoring/standard.ref")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
