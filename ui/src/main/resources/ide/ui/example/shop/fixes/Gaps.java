package shop.fixes;

/**
 * More problems on purpose, each with the fix an IDE offers (Alt+Enter on the line, or a click on its bulb):
 *  - label: a long given a text; change its type to String.
 *  - twice(): calls base() from a static method; make base() static.
 *  - title(): answers a text where a long goes; change its return type.
 *  - unused(): a private method nothing calls, a warning; remove it.
 *  - Square: it does not implement what Shape leaves abstract; implement the methods, or make it abstract.
 */
public class Gaps {
    long label = "none";

    static int twice() {
        return base() * 2;
    }

    int base() {
        return 21;
    }

    long title() {
        return "untitled";
    }

    private void unused() {
    }
}

abstract class Shape {
    abstract int side();

    abstract String name(String prefix);
}

class Square extends Shape {
}
