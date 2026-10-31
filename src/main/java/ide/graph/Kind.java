package ide.graph;

public enum Kind {
    CLASS, INTERFACE, FIELD, METHOD;

    public String prefix() {
        return switch (this) {
            case CLASS, INTERFACE -> "type";
            case FIELD -> "field";
            case METHOD -> "method";
        };
    }
}
