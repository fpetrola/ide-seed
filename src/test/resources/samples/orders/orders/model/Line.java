package orders.model;

public class Line {
    String product;
    int qty;
    double price;

    public Line(String product, int qty, double price) {
        this.product = product;
        this.qty = qty;
        this.price = price;
    }

    public double amount() {
        return qty * price;
    }
}
