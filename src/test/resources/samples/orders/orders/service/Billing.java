package orders.service;

import orders.model.Customer;
import orders.model.Order;
import orders.model.Priced;
import orders.model.Status;

public class Billing {

    public double charge(Order order) {
        Customer customer = order.customer;
        double total = order.total();
        if (customer.discount > 0) {
            total = total * (100 - customer.discount) / 100;
        }
        order.status = Status.PAID;
        return total;
    }

    public String label(Order order) {
        return order.customer.name + ": " + priced(order).total();
    }

    public void loyalty(Customer customer) {
        customer.discount += 5;
    }

    private Priced priced(Order order) {
        return order;
    }
}
