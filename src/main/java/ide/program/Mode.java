package ide.program;

/**
 * How a reference uses its target. Uses happen in bodies: READ, WRITE, UPDATE (compound assignment, ++/--), CALL,
 * NEW, TYPE (a type mentioned anywhere). Links are declarations: EXTENDS, IMPLEMENTS, DECLARED (the type a field,
 * parameter or method is declared with). The hierarchy and the verifier treat links like any other reference.
 */
public enum Mode {
    READ, WRITE, UPDATE, CALL, NEW, TYPE, EXTENDS, IMPLEMENTS, DECLARED;

    /** The lowercase name scripts and codes use ({@code retarget.update}). */
    public String label() {
        return name().toLowerCase();
    }

    public static Mode of(String label) {
        return valueOf(label.toUpperCase());
    }

    /** A mention of a type: in a body, or as a declaration link. */
    public boolean isTypeUse() {
        return this == TYPE || isLink();
    }

    public boolean isLink() {
        return this == EXTENDS || this == IMPLEMENTS || this == DECLARED;
    }
}
