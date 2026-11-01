package shop.model;

/**
 * Try here:
 *  - Alt+Shift+R on "name" (rename): Cart and Report follow.
 *  - Alt+Shift+T on "price", then encapsulate-field: getPrice/setPrice, and Cart goes through them.
 *  - Alt+Shift+T on "price", then rename-field-with-accessors once it has them.
 *  - Ctrl+Shift+G on "stock" (references), Ctrl+Alt+H on "available" (callers).
 */
public class Product {
    public String name;
    public double price;
    public int stock;

    public Product(String name, double price) {
        this.name = name;
        this.price = price;
    }

    public boolean available() {
        return stock > 0;
    }
}
