package orders.model;

public class Customer extends Entity {
    public String name;
    public int discount;

    public Customer(String name) {
        this.name = name;
    }
}
