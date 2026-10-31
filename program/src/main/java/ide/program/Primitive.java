package ide.program;

/**
 * A request to change the program, in no language. A projection applies it in its language and answers the
 * {@link Change} it made on purpose. Elements are given by id; references by their id ({@code site@ordinal}).
 */
public sealed interface Primitive {

    /**
     * What applying this primitive means to change, as far as the program alone tells: what a verifier must expect
     * and what a repair may not undo. A projection completes it with what only it knows (what an extract moved).
     *
     * @param before   the program the primitive applies to
     * @param produced the element the primitive created, if any
     */
    default Change intent(Program before, ElementId produced) {
        return switch (this) {
            case Create c -> c.kind() == Kind.PACKAGE ? Change.produced(produced)
                    : Change.produced(produced).pin(produced, Change.Aspect.PARENT, Change.Aspect.NAME);
            case Delete d -> Change.NONE.expect(new Change.UsesGone(d.element()));
            case Move m -> Change.NONE.pin(m.element(), Change.Aspect.PARENT);
            case Rename r -> Change.NONE.pin(r.element(), Change.Aspect.NAME);
            case Set s -> Fact.VISIBILITY.label().equals(s.attribute())
                    ? Change.NONE.pin(s.element(), Change.Aspect.VISIBILITY) : Change.NONE;
            case Retarget r -> {
                var reference = before.reference(r.reference());
                yield Change.NONE.expect(new Change.Retargeted(reference.site(), reference.target(), r.target()));
            }
            case Extract e -> Change.produced(produced);
            case Inline i -> {
                var reference = before.reference(i.reference());
                yield Change.NONE.expect(new Change.Dropped(reference.site(), reference.target()));
            }
        };
    }

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
