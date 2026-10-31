package ide.graph;

/** A use of {@code target} from inside element {@code site}. */
public record Reference(ElementId site, ElementId target) {
}
