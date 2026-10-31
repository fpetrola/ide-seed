package ide.projection;

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
}
