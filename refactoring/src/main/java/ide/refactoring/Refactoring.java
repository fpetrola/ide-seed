package ide.refactoring;

import ide.core.Param;

import java.util.List;

/** A change expressed as primitives and calls to other refactorings; it need not be careful, the verifier is. */
public interface Refactoring {

    String id();

    default String description() {
        return "";
    }

    /** Declared parameters, in positional order. */
    List<Param> params();

    /** primitive, composite (Java) or script. */
    default String level() {
        return "composite";
    }

    /** Applies the change; returns the id of the element it produced, if any (else null). */
    String apply(Ctx ctx, Args args);
}
