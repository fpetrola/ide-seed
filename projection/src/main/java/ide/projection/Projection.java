package ide.projection;

import ide.program.ElementId;
import ide.program.Kind;
import ide.program.Program;

import java.util.List;

/** A program that can be changed only through primitives; every change is visible in the next {@link #graph()}. */
public interface Projection {

    Program graph();

    /** Current compiler errors. */
    List<Diagnostic> diagnostics();

    /** Where completion is asked in {@code text} (the editor's possibly unparsable version of {@code path}). */
    java.util.Optional<CompletionContext> completionContext(String path, String text, int offset);

    /** Captures the whole program state (text and identity). */
    Object checkpoint();

    /** Returns to a state captured by {@link #checkpoint()}. */
    void rollback(Object checkpoint);

    /** Creates an empty element of {@code kind} named {@code name} inside {@code parent}. */
    ElementId create(Kind kind, ElementId parent, String name);

    default void move(ElementId member, ElementId newParent) {
        move(member, newParent, null);
    }

    /** Moves a member into {@code newParent}, just before {@code before} (a member there), or at the end if null. */
    void move(ElementId member, ElementId newParent, ElementId before);

    /**
     * Replaces the text of a source file (a manual edit): elements keep their identity while their address stays,
     * new ones are born, missing ones are gone. Refuses text that does not parse ({@code projection.unparsable}).
     */
    void replaceSource(String path, String text);

    /** Deletes an element that nothing references. */
    void delete(ElementId element);

    /** Renames an element; every use of it is rewritten. */
    void rename(ElementId element, String name);

    /** Sets an attribute; {@code body} takes a template where {@code #{id}} stands for the element {@code id}. */
    void set(ElementId element, String attribute, String value);

    /**
     * Replaces a call by the body of the called method (a single {@code return expr;}), substituting arguments;
     * refuses what would change how often or in which order things are evaluated.
     */
    void inline(String call);

    /**
     * Moves the statements of {@code from} on lines {@code fragment} ("first-last") into a new method {@code name}
     * and calls it there; inputs become parameters, at most one value used afterwards becomes the result.
     * Refuses fragments with jumps out of them or more than one output.
     */
    ElementId extract(ElementId from, String fragment, String name);

    /** Points reference {@code ref} at {@code target}, changing the shape of the site as needed. */
    void retarget(String ref, ElementId target);
}
