package ide.program;

public enum Kind {
    PACKAGE, CLASS, INTERFACE, ENUM, FIELD, CONSTRUCTOR, METHOD, PARAM, LOCAL;

    public boolean isType() {
        return this == CLASS || this == INTERFACE || this == ENUM;
    }

    public String prefix() {
        return switch (this) {
            case PACKAGE -> "pkg";
            case CLASS, INTERFACE, ENUM -> "type";
            case FIELD -> "field";
            case CONSTRUCTOR -> "ctor";
            case METHOD -> "method";
            case PARAM -> "param";
            case LOCAL -> "local";
        };
    }
}
