package ide.program;

/**
 * A use of, or a link to, {@code target}, written as {@code name}, from inside element {@code site}, made on
 * {@code receiver}.
 */
public record Reference(String id, ElementId site, ElementId target, String name, Mode mode, Receiver receiver) {

    /**
     * What a use is made on: the object its site belongs to ({@code SELF}: written alone, or through this or super), a
     * type ({@code TYPE}), or another object ({@code OTHER}). A link, a type used or a new object is made on nothing.
     */
    public enum Receiver {
        SELF, TYPE, OTHER, NONE;

        /** What a use is made on when nothing else is said: a member on the object itself, anything else on nothing. */
        public static Receiver of(Mode mode) {
            return switch (mode) {
                case READ, WRITE, UPDATE, CALL -> SELF;
                default -> NONE;
            };
        }
    }

    /** A use made on what its mode says when nothing else is said ({@link Receiver#of}). */
    public Reference(String id, ElementId site, ElementId target, String name, Mode mode) {
        this(id, site, target, name, mode, Receiver.of(mode));
    }
}
