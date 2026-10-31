package ide.graph;

import java.util.List;

/** A program that can be changed only through primitives; every change is visible in the next {@link #graph()}. */
public interface Program {

    ProgramGraph graph();

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

    /** Renames an element; every use of it is rewritten. */
    void rename(ElementId element, String name);

    /** Sets an attribute; {@code body} takes a template where {@code #{id}} stands for the element {@code id}. */
    void set(ElementId element, String attribute, String value);

    /** Points reference {@code ref} at {@code target}, changing the shape of the site as needed. */
    void retarget(String ref, ElementId target);
}
