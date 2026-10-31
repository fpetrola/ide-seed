package ide.graph;

/** What is being completed: members of {@code receiver} starting with {@code prefix}, as seen from {@code site}. */
public record CompletionContext(ElementId receiver, String prefix, ElementId site) {
}
