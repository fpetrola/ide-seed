package ide.refactoring;

import ide.core.Param;
import ide.program.ElementId;

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

    /** Applies the change in a run; answers the element it produced or worked on, if any (else null). */
    ElementId apply(Run run, Args args);
}
