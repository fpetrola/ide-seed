package ide.program;

/**
 * What is known about an element beyond its name, kind and links: a closed set, grown only when a refactoring or
 * a view needs a fact to decide. VISIBILITY takes the values the language defines; STATIC, ABSTRACT and FINAL are
 * {@code true}/{@code false}; TYPE is the declared type as written; SUPERCLASS the class a class extends, as written,
 * whether it is in the workspace or not; BODY the method body text; FILE the source
 * file of a top-level type (a fact of the projection that navigation needs); LIBRARY {@code true} for an element
 * declared outside the workspace, in a library: the program names it, and nothing changes it.
 */
public enum Fact {
    VISIBILITY, STATIC, ABSTRACT, FINAL, TYPE, SUPERCLASS, BODY, FILE, LIBRARY;

    /** The lowercase name scripts and the {@code set} primitive use. */
    public String label() {
        return name().toLowerCase();
    }

    public static Fact of(String label) {
        return valueOf(label.toUpperCase());
    }
}
