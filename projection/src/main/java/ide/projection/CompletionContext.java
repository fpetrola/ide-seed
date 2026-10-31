package ide.projection;

import ide.program.ElementId;

import java.util.List;

/**
 * What is being completed: members of {@code receiver} starting with {@code prefix}, as seen from {@code site};
 * only static ones when the receiver is a type name. For a bare name, {@code locals} are the local variables and
 * parameters in scope, nearest first.
 */
public record CompletionContext(ElementId receiver, String prefix, ElementId site, boolean staticOnly,
                                List<String> locals) {
}
