package orders.model;

import java.util.ArrayList;
import java.util.List;

public class Order extends Entity implements Priced {
    public Customer customer;
    public Status status = Status.NEW;
    private final List<Line> lines = new ArrayList<>();

    public void add(Line line) {
        lines.add(line);
    }

    public List<Line> lines() {
        return lines;
    }

    @Override
    public double total() {
        double sum = 0;
        for (Line line : lines) {
            sum += line.amount();
        }
        return sum;
    }
}
