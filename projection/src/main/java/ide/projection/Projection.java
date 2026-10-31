package ide.projection;

import ide.program.Change;
import ide.program.Primitive;
import ide.program.Program;

import java.util.List;
import java.util.Optional;

/**
 * A language driver: reads the program from sources and changes it only through primitives. Every change is visible
 * in the next {@link #program()}.
 */
public interface Projection {

    /** The program as it is now. */
    Program program();

    /**
     * Applies a primitive in this projection's language and answers what it changed on purpose: what it produced,
     * what a verifier must expect, what a repair may not undo. A primitive that cannot be applied safely is refused
     * with {@link PrimitiveRejected} and changes nothing.
     */
    Change apply(Primitive primitive);

    /**
     * Replaces the text of a source file (a manual edit): elements keep their identity while their address stays,
     * new ones are born, missing ones are gone. Refuses text that does not parse ({@code projection.unparsable}).
     */
    void edit(String path, String text);

    /** Captures everything this projection is: sources, identity, program. */
    Snapshot snapshot();

    /** Returns to a state captured by {@link #snapshot()} of this projection. */
    void restore(Snapshot snapshot);

    /** The language's semantics the products above ask about: access, visibilities, imports. */
    LanguageRules rules();

    /** Current compiler errors. */
    List<Diagnostic> diagnostics();

    /** Where completion is asked in {@code text} (the editor's possibly unparsable version of {@code path}). */
    Optional<CompletionContext> completionContext(String path, String text, int offset);

    /** A projection's saved state; only the projection that took it can restore it. */
    interface Snapshot {
    }
}
