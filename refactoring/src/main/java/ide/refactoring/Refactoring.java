package ide.refactoring;

import ide.core.Param;
import ide.program.ElementId;

import java.util.List;
import java.util.Map;

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

    /**
     * What it suggests for the parameters not given yet, from those given (an IDE's default names): text by
     * parameter name. What cannot be suggested is left out.
     */
    default Map<String, String> suggestions(Run run, Args args) {
        return Map.of();
    }

    /** Applies the change in a run; answers the element it produced or worked on, if any (else null). */
    ElementId apply(Run run, Args args);
}
