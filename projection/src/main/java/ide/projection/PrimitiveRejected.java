package ide.projection;

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

    /** Every projection refuses to change an element of a library: the program names it, nothing changes it. */
    public static void unlessInWorkspace(Program before, Primitive primitive) {
        for (var changed : primitive.changed()) {
            if (before.contains(changed) && before.element(changed).inLibrary()) {
                throw new PrimitiveRejected("projection.library", before.element(changed).qualified() + " belongs to a library");
            }
        }
    }
}
