package ide.program;

/** Stable, opaque identity of an element; survives rename and move. */
public record ElementId(String value) {

    @Override
    public String toString() {
        return value;
    }
}
