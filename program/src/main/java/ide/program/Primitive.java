package ide.program;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
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
                if (c.kind() == Kind.STATEMENT && c.facts().containsKey(Fact.BODY)) { // what the statement says, its method makes
                    for (var use : Body.parse(c.facts().get(Fact.BODY), before, c.parent()).uses()) {
                        change = change.expect(new Change.Added(c.parent(), use.element(), before.element(use.element()).name(), use.mode()));
                    }
                }
                yield c.facts().containsKey(Fact.VISIBILITY) ? change.pin(produced, Change.Aspect.VISIBILITY) : change;
            }
            case Delete d -> Change.NONE.expect(d.into() == null ? new Change.UsesGone(d.element()) : new Change.Merged(d.element(), d.into()));
            case Move m -> {
                var change = Change.NONE.pin(m.element(), Change.Aspect.PARENT);
                var moved = before.element(m.element());
                if (moved.kind() == Kind.STATEMENT && !m.parent().equals(moved.parent())) { // the uses made in it are the other method's
                    for (var use : before.referencesIn(m.element())) change = change.expect(new Change.Resited(use.target(), use.site(), m.parent()));
                }
                yield change;
            }
            case Rename r -> Change.NONE.pin(r.element(), Change.Aspect.NAME);
            case Set s -> {
                if ("initializer".equals(s.attribute()) && Body.names(s.value())) yield initializer(before, s.element(), s.value());
                yield java.util.Arrays.stream(Fact.values()).filter(f -> f.label().equals(s.attribute())).findFirst()
                        .flatMap(Change.Aspect::of).map(aspect -> Change.NONE.pin(s.element(), aspect)).orElse(Change.NONE);
            }
            case Link l -> {
                if (l.mode() == Mode.IMPLEMENTS) yield Change.NONE; // implements adds one more
                yield before.link(l.element(), l.mode()).filter(old -> !old.target().equals(l.target()))
                        .map(old -> Change.NONE.expect(new Change.Retargeted(l.element(), old.target(), l.target())))
                        .orElse(Change.NONE);
            }
            case Retarget r -> {
                if (before.unresolved(r.reference()).isPresent()) yield Change.NONE.expect(new Change.Bound(r.reference(), r.target()));
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

    /** A field's start said in no language: its uses take the place of the old start's. */
    private static Change initializer(Program before, ElementId field, String text) {
        var change = Change.NONE;
        for (var use : before.referencesFrom(field)) {
            if (!use.mode().isLink()) change = change.expect(new Change.Dropped(field, use.target()));
        }
        for (var use : Body.uses(Body.value(text, before, field))) {
            change = change.expect(new Change.Added(field, use.element(), before.element(use.element()).name(), use.mode()));
        }
        return change;
    }

    /** The elements this primitive changes; a projection refuses it when one of them belongs to a library. */
    default List<ElementId> changed() {
        return switch (this) {
            case Create c -> c.parent() == null ? List.of() : List.of(c.parent());
            case Delete d -> List.of(d.element());
            case Move m -> List.of(m.element(), m.parent());
            case Rename r -> List.of(r.element());
            case Set s -> List.of(s.element());
            case Link l -> List.of(l.element());
            case Extract e -> List.of(e.method());
            case Retarget r -> List.of(); // the code that changes is where the use is: always the workspace
            case Inline i -> List.of();
        };
    }

    /**
     * An element of {@code kind} named {@code name} inside {@code parent} (none for a package), born with
     * {@code facts} (a type as written, a visibility...) or the language's defaults. A parameter of a method that is
     * called needs the {@code argument} every call passes for it. A statement is said in no language, as its body: it
     * goes at the end of its method's body, or at the start if it calls a constructor, which comes first.
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

    /**
     * Deletes an element nothing references; or, given {@code into}, one whose uses bind {@code into} from then on: a
     * field goes into a field, a method into a method, and the two are one.
     */
    @Summary("Deletes an element nothing references, or one whose uses go to another")
    record Delete(ElementId element, @Optional ElementId into) implements Primitive {

        public Delete(ElementId element) {
            this(element, null);
        }
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

    /**
     * Moves a fragment of a method into a new method that the method calls: statements of its body that follow one
     * another, named by their ids ({@code statement:7,statement:8}), or a fragment only the projection understands.
     */
    @Summary("Moves a fragment of a method into a new method, and calls it")
    record Extract(ElementId method, String fragment, String name) implements Primitive {
    }

    /** Replaces a use by what it names: a call by the body of the called method, a read of a local by its value. */
    @Summary("Replaces a call by the body of the called method, or a read of a local by its value")
    record Inline(String reference) implements Primitive {
    }
}
