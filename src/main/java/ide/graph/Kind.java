package ide.graph;

public enum Kind {
    PACKAGE, CLASS, INTERFACE, FIELD, METHOD, PARAM;

    public String prefix() {
        return switch (this) {
            case PACKAGE -> "pkg";
            case CLASS, INTERFACE -> "type";
            case FIELD -> "field";
            case METHOD -> "method";
            case PARAM -> "param";
        };
    }
}
