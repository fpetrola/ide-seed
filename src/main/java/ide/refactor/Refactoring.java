package ide.refactor;

/** A change expressed as primitives and calls to other refactorings; it need not be careful, the verifier is. */
public interface Refactoring {

    String id();

    void apply(Ctx ctx, Args args);
}
