package ide.program;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;

/**
 * A request to change the program, in no language. A projection applies it in its language and answers the
 * {@link Change} it made on purpose. Elements are given by id; references by their id ({@code site@ordinal}).
 * Each primitive states what it does ({@link Summary}) and which of its parts may be left out ({@link Optional}),
 * so that tools can offer it without knowing it.
 */
public sealed interface Primitive {

    /** What a primitive does, in one line. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Summary {
        String value();
    }

    /** A part of a primitive that may be left out (null, or empty). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.RECORD_COMPONENT)
    @interface Optional {
    }

    /**
     * What applying this primitive means to change, as far as the program alone tells: what a verifier must expect
     * and what a repair may not undo. A projection completes it with what only it knows (what an extract moved).
     *
     * @param before   the program the primitive applies to
     * @param produced the element the primitive created, if any
     */
    default Change intent(Program before, ElementId produced) {
        return switch (this) {
            case Create c -> {
                if (c.kind() == Kind.PACKAGE) yield Change.produced(produced);
                var change = Change.produced(produced).pin(produced, Change.Aspect.PARENT, Change.Aspect.NAME);
                yield c.facts().containsKey(Fact.VISIBILITY) ? change.pin(produced, Change.Aspect.VISIBILITY) : change;
            }
            case Delete d -> Change.NONE.expect(new Change.UsesGone(d.element()));
            case Move m -> Change.NONE.pin(m.element(), Change.Aspect.PARENT);
            case Rename r -> Change.NONE.pin(r.element(), Change.Aspect.NAME);
            case Set s -> Fact.VISIBILITY.label().equals(s.attribute())
                    ? Change.NONE.pin(s.element(), Change.Aspect.VISIBILITY) : Change.NONE;
            case Link l -> {
                var old = switch (l.mode()) {
                    case EXTENDS -> before.superclass(l.element());
                    case DECLARED -> before.declaredType(l.element());
                    default -> java.util.Optional.<Element>empty(); // implements adds one more
                };
                yield old.filter(o -> !o.id().equals(l.target()))
                        .map(o -> Change.NONE.expect(new Change.Retargeted(l.element(), o.id(), l.target())))
                        .orElse(Change.NONE);
            }
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

    /**
     * An element of {@code kind} named {@code name} inside {@code parent} (none for a package), born with
     * {@code facts} (a type as written, a visibility...) or the language's defaults. A parameter of a method that is
     * called needs the {@code argument} every call passes for it.
     */
    @Summary("Creates an element, with the facts it is born with")
    record Create(Kind kind, @Optional ElementId parent, String name, @Optional Map<Fact, String> facts,
                  @Optional String argument) implements Primitive {

        public Create {
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }

        public Create(Kind kind, ElementId parent, String name) {
            this(kind, parent, name, Map.of(), null);
        }
    }

    @Summary("Deletes an element nothing references")
    record Delete(ElementId element) implements Primitive {
    }

    /**
     * Moves an element into {@code parent}, just before {@code before} (a child there), or at the end if none. A
     * parameter moves within its method, and every call's arguments follow.
     */
    @Summary("Moves an element into another parent, or a parameter within its method")
    record Move(ElementId element, ElementId parent, @Optional ElementId before) implements Primitive {
    }

    @Summary("Renames an element and every use of it")
    record Rename(ElementId element, String name) implements Primitive {
    }

    /**
     * Sets an attribute of an element as text: a fact (a declared type or a superclass as written, a visibility, a
     * body), or one only the projection knows. To make a declaration name another workspace type, use {@link Link}.
     */
    @Summary("Sets an attribute of an element, as text")
    record Set(ElementId element, String attribute, String value) implements Primitive {
    }

    /**
     * Makes a declaration name a workspace type: the class it extends, one more interface it implements, or the type
     * a field, parameter or method is declared with.
     */
    @Summary("Makes a declaration name a workspace type: extends, implements or declared")
    record Link(ElementId element, Mode mode, ElementId target) implements Primitive {
        public Link {
            if (!mode.isLink()) throw new IllegalArgumentException("not a link: " + mode);
        }
    }

    @Summary("Points a reference at another element, changing the shape of the use as needed")
    record Retarget(String reference, ElementId target) implements Primitive {
    }

    /** Moves a fragment of a method, opaque to all but the projection, into a new method that the method calls. */
    @Summary("Moves a fragment of a method into a new method, and calls it")
    record Extract(ElementId method, String fragment, String name) implements Primitive {
    }

    @Summary("Replaces a call by the body of the called method")
    record Inline(String reference) implements Primitive {
    }
}
