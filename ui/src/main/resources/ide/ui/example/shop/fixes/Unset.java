package shop.fixes;

/**
 * An error on purpose: "limit" is final and nothing gives it a value. Ctrl+1 on it: give it one in its declaration
 * (the fix asks which, and proposes 0), or make it not final.
 */
public class Unset {
    private final int limit;

    int twice() {
        return limit * 2;
    }
}
