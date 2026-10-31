package ide.graph;

public enum Kind {
    PACKAGE, CLASS, INTERFACE, FIELD, METHOD;

    public String prefix() {
        return switch (this) {
            case PACKAGE -> "pkg";
            case CLASS, INTERFACE -> "type";
            case FIELD -> "field";
            case METHOD -> "method";
        };
    }
}
