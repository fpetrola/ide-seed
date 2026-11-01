package ide.projection;

import ide.program.ElementId;
import ide.program.Program;

import java.util.List;

/** Language semantics the language-neutral layers ask about. */
public interface LanguageRules {

    /** Whether code inside {@code site} may access {@code target}. */
    boolean accessible(Program graph, ElementId site, ElementId target);

    /** Visibility values from most to least restrictive. */
    List<String> visibilities();

    /** The visibilities this element may have, from most to least restrictive. */
    default List<String> visibilities(Program graph, ElementId element) {
        return visibilities();
    }
}
