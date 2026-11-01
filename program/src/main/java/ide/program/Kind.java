package ide.program;

public enum Kind {
    PACKAGE, CLASS, INTERFACE, ENUM, FIELD, CONSTRUCTOR, METHOD, PARAM, LOCAL, STATEMENT;

    public boolean isType() {
        return this == CLASS || this == INTERFACE || this == ENUM;
    }

    /** What a body is made of: its statements, and the locals they declare. */
    public boolean inBody() {
        return this == LOCAL || this == STATEMENT;
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
            case STATEMENT -> "statement";
        };
    }
}
