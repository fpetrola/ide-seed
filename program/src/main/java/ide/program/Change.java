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

    /** An aspect of an element that the change set on purpose. */
    public record Pin(ElementId element, Aspect aspect) {
    }

    public enum Aspect {
        PARENT, NAME, VISIBILITY
    }
}
