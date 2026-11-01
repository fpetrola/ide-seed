package ide.program;

import java.util.ArrayList;
import java.util.List;

/**
 * What a primitive did on purpose. {@code produced} is the element it created, or null. {@code expectations} are the
 * differences between before and after that a verifier must expect instead of report. {@code pins} are the aspects
 * of elements that were set on purpose, which a repair may not undo.
 */
public record Change(ElementId produced, List<Expectation> expectations, List<Pin> pins) {

    public static final Change NONE = new Change(null, List.of(), List.of());

    public Change {
        expectations = List.copyOf(expectations);
        pins = List.copyOf(pins);
    }

    public static Change produced(ElementId element) {
        return new Change(element, List.of(), List.of());
    }

    public Change expect(Expectation expectation) {
        var more = new ArrayList<>(expectations);
        more.add(expectation);
        return new Change(produced, more, pins);
    }

    public Change pin(ElementId element, Aspect... aspects) {
        var more = new ArrayList<>(pins);
        for (var aspect : aspects) more.add(new Pin(element, aspect));
        return new Change(produced, expectations, more);
    }

    /** A difference between before and after that the change made on purpose. */
    public sealed interface Expectation {
    }

    /** The reference from {@code site} that bound {@code from} binds {@code to} now. */
    public record Retargeted(ElementId site, ElementId from, ElementId to) implements Expectation {
    }

    /** A use of {@code target} made from {@code from} is made from {@code to} now. */
    public record Resited(ElementId target, ElementId from, ElementId to) implements Expectation {
    }

    /** A use of {@code target} from {@code site} is gone. */
    public record Dropped(ElementId site, ElementId target) implements Expectation {
    }

    /** A use of {@code target}, written {@code name}, is made from {@code site} now, as {@code mode}. */
    public record Added(ElementId site, ElementId target, String name, Mode mode) implements Expectation {
    }

    /** The use {@code use}, which bound nothing, binds {@code to} now. */
    public record Bound(String use, ElementId to) implements Expectation {
    }

    /** Every use made from inside {@code element} is gone with it. */
    public record UsesGone(ElementId element) implements Expectation {
    }

    /**
     * {@code element} is gone into {@code into}: its uses bind {@code into}, the uses made from inside it are gone, and
     * what overrode it overrides what {@code into} is.
     */
    public record Merged(ElementId element, ElementId into) implements Expectation {
    }

    /** An aspect of an element that the change set on purpose. */
    public record Pin(ElementId element, Aspect aspect) {
    }

    /** Where an element is, its name, and the facts a {@code Set} gives it, which a repair may not take back. */
    public enum Aspect {
        PARENT, NAME, VISIBILITY, STATIC, ABSTRACT, FINAL, TYPE;

        /** The aspect a set of a fact pins, if it pins one. */
        public static java.util.Optional<Aspect> of(Fact fact) {
            return switch (fact) {
                case VISIBILITY -> java.util.Optional.of(VISIBILITY);
                case STATIC -> java.util.Optional.of(STATIC);
                case ABSTRACT -> java.util.Optional.of(ABSTRACT);
                case FINAL -> java.util.Optional.of(FINAL);
                case TYPE -> java.util.Optional.of(TYPE);
                default -> java.util.Optional.empty();
            };
        }

        /** The fact it is, for a pinned fact. */
        public Fact fact() {
            return switch (this) {
                case PARENT, NAME -> null;
                default -> Fact.valueOf(name());
            };
        }
    }
}
