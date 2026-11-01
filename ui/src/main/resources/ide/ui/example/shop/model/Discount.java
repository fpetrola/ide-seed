package shop.model;

/**
 * Try here:
 *  - Alt+Shift+T on the constructor "Discount", then introduce-factory: Cart's "new Discount(...)" becomes Discount.of(...).
 *  - Alt+Shift+T on "rate", then merge-duplicate with keep = percent: same body; the uses of rate() go to percent().
 *  - Alt+Shift+C on "apply" (change-signature).
 */
public class Discount {
    private final int percent;

    public Discount(int percent) {
        this.percent = percent;
    }

    public int percent() {
        return percent;
    }

    public int rate() {
        return percent;
    }

    public double apply(double amount, boolean rounded) {
        var discounted = amount * (100 - percent) / 100;
        return rounded ? Math.round(discounted) : discounted;
    }
}
