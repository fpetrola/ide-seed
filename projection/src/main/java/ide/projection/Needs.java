package ide.projection;

import java.util.List;

/**
 * What a use that binds nothing needs of what would bind it, as its language writes types: the type its place expects
 * of its value ({@code expected}; for a call whose value nothing takes, the language's "nothing"), and, for a call,
 * what it passes. Asked of the projection for one use, when a fix needs it; not part of the program.
 */
public record Needs(String expected, List<Argument> arguments) {

    /** What a call passes: a name for it, as the language would name a parameter that takes it, and its type. */
    public record Argument(String name, String type) {
    }

    public Needs {
        arguments = List.copyOf(arguments);
    }
}
