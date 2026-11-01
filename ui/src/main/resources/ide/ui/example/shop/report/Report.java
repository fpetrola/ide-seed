package shop.report;

import shop.cart.Cart;
import shop.model.Product;

/**
 * Try here:
 *  - Alt+Shift+T on "Line", then collapse-hierarchy: Line goes into Entry, a nested class into another.
 *  - Alt+Shift+T on "label", then generate-getter or generate-setter.
 *  - Ctrl+Alt+H on "describe" (callers).
 */
public class Report {

    static class Entry {
        String label;
    }

    static class Line extends Entry {
        int quantity;
    }

    String describe(Product product, Cart cart) {
        var line = new Line();
        line.label = product.name;
        line.quantity = 1;
        return line.label + " x" + line.quantity + ": " + cart.total();
    }
}
