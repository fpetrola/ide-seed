package ide.coherence;

import ide.graph.Program;

import java.util.function.Consumer;

/** A candidate change proposed for a violation or diagnostic; whether it worked is for the verifier to say. */
public record Plan(String title, Consumer<Program> apply) {
}
