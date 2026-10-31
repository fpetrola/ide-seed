package ide.program;

/**
 * A request to change the program, in no language. A projection applies it in its language and answers the
 * {@link Change} it made on purpose. Elements are given by id; references by their id ({@code site@ordinal}).
 */
public sealed interface Primitive {

    /** An empty element of {@code kind} named {@code name} inside {@code parent} (null for a package). */
    record Create(Kind kind, ElementId parent, String name) implements Primitive {
    }

    /** Deletes an element that nothing references. */
    record Delete(ElementId element) implements Primitive {
    }

    /** Moves an element into {@code parent}, just before {@code before} (a child there), or at the end if null. */
    record Move(ElementId element, ElementId parent, ElementId before) implements Primitive {
    }

    /** Renames an element; every use of it follows. */
    record Rename(ElementId element, String name) implements Primitive {
    }

    /** Sets an attribute of an element: a fact, or one only the projection knows. */
    record Set(ElementId element, String attribute, String value) implements Primitive {
    }

    /** Points one reference at another target, changing the shape of the use as needed. */
    record Retarget(String reference, ElementId target) implements Primitive {
    }

    /** Moves a fragment of a method, opaque to all but the projection, into a new method that the method calls. */
    record Extract(ElementId method, String fragment, String name) implements Primitive {
    }

    /** Replaces a call by the body of the called method. */
    record Inline(String reference) implements Primitive {
    }
}
