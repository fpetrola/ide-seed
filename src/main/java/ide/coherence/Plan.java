package ide.coherence;

import ide.graph.Program;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A candidate change proposed for a violation or diagnostic; whether it worked is for the verifier to say.
 * A plan with {@code asks} has holes the user fills (quick fixes only, never automatic repair).
 */
public record Plan(String title, List<Ask> asks, BiConsumer<Program, Map<String, String>> apply) {

    /** A question with a default answer. */
    public record Ask(String name, String question, String defaultAnswer) {
    }

    public void apply(Program program) {
        apply.accept(program, Map.of());
    }
}
