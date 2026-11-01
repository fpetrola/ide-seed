package ide.projection;

import ide.program.Change;
import ide.program.ElementId;
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

    /** The language's semantics the products above ask about: access, visibilities, default values. */
    LanguageRules rules();

    /** Current compiler errors. */
    List<Diagnostic> diagnostics();

    /** Where completion is asked in {@code text} (the editor's possibly unparsable version of {@code path}). */
    Optional<CompletionContext> completionContext(String path, String text, int offset);

    /** Where an element is declared: the start of its name; nowhere for what has no text (a package, a library type). */
    Optional<Location> locate(ElementId element);

    /** The element whose name is at a line and column of a file: the one declared there, or the one a use there binds. */
    Optional<ElementId> elementAt(String path, int line, int column);

    /** The innermost element whose declaration a line and column of a file is inside; nothing in a projection with no text. */
    Optional<ElementId> inside(String path, int line, int column);

    /**
     * What a use that binds nothing ({@link ide.program.Unresolved}, by id) needs of what would bind it: the type its
     * place expects and what it passes, as the language writes them. Nothing for another id.
     */
    Optional<Needs> needs(String use);

    /**
     * A method's or a constructor's body said in no language ({@link ide.program.Body}), when all of it can be said so;
     * nothing when something in it cannot, or for another element.
     */
    Optional<String> said(ElementId callable);

    /** A projection's saved state; only the projection that took it can restore it. */
    interface Snapshot {
    }
}
