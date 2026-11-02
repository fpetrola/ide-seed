package ide.projection;

import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;

/** A primitive refused to apply because the change cannot be expressed safely; aborts the transaction. */
public final class PrimitiveRejected extends RuntimeException {

    private final String code;

    public PrimitiveRejected(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Every projection merges a field only into a field, a method only into a method, a local only into a field of its name. */
    public static void unlessMergeable(Program before, Primitive.Delete delete) {
        if (delete.into() == null) return;
        var element = before.element(delete.element());
        var into = before.element(delete.into());
        var local = element.kind() == Kind.LOCAL && into.kind() == Kind.FIELD;
        if (!local && (element.kind() != into.kind() || element.kind() != Kind.FIELD && element.kind() != Kind.METHOD)) {
            throw new PrimitiveRejected("delete.into", element.qualified() + " cannot go into " + into.qualified()
                    + ": a field goes into a field, a method into a method, a local into a field");
        }
        if (local && !element.name().equals(into.name())) {
            throw new PrimitiveRejected("delete.into", element.qualified() + " goes into a field of its name, not " + into.qualified());
        }
    }

    /** Every projection refuses to change an element of a library: the program names it, nothing changes it. */
    public static void unlessInWorkspace(Program before, Primitive primitive) {
        for (var changed : primitive.changed()) {
            if (before.contains(changed) && before.element(changed).inLibrary()) {
                throw new PrimitiveRejected("projection.library", before.element(changed).qualified() + " belongs to a library");
            }
        }
    }
}
