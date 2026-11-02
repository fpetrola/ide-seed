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

    /**
     * Whether it may change what the program does (when, or how many times, something runs): only a person chooses it,
     * knowing so; nothing that verifies bindings can tell.
     */
    default boolean changesMeaning() {
        return false;
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

    /**
     * The elements a parameter may take, given the parameters given (the fields of the type chosen), as qualified
     * names; nothing if the refactoring does not narrow them, and any element of the kinds it takes will do.
     */
    default java.util.Optional<List<String>> options(Run run, Args args, String param) {
        return java.util.Optional.empty();
    }

    /** Applies the change in a run; answers the element it produced or worked on, if any (else null). */
    ElementId apply(Run run, Args args);
}
