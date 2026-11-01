package shop.cart;

import shop.model.Discount;
import shop.model.Product;

import java.util.ArrayList;
import java.util.List;

/**
 * Try here:
 *  - Completion: write "product." in total() and wait, or Ctrl+Space after a few letters.
 *  - F3 or Ctrl+Click on "Product", "price", "available": go to their declarations; Alt+Left comes back.
 *  - Alt+Shift+T on "subtotal", then inline-method: its one return goes where it is called.
 *  - Alt+Shift+T on "total", then extract-method with lines of its body (for example 31-32) and a name.
 *  - Alt+Shift+T on "Cart", then extract-superclass or extract-interface.
 *  - Ctrl+O: the outline of this file; Ctrl+Shift+T: any type of the project.
 */
public class Cart {
    private final List<Product> products = new ArrayList<>();
    private Discount discount = new Discount(10);

    public void add(Product product) {
        if (product.available()) {
            products.add(product);
        }
    }

    public double total() {
        double sum = 0.0;
        for (Product product : products) {
            sum = sum + product.price;
            product.stock = product.stock - 1;
        }
        return discount.apply(subtotal(sum), false);
    }

    double subtotal(double sum) {
        return sum;
    }

    public int discountRate() {
        return discount.rate();
    }
}
