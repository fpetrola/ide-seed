package shop;

public interface Item {
    String code();

    default String label() {
        return "[" + code() + "]";
    }
}
