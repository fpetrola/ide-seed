package ide.refactor;

/** A change expressed as primitives and calls to other refactorings; it need not be careful, the verifier is. */
public interface Refactoring {

    String id();

    /** Applies the change; returns the id of the element it produced, if any (else null). */
    String apply(Ctx ctx, Args args);
}
