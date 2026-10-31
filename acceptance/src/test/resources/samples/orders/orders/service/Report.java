package orders.service;

import orders.model.Line;
import orders.model.Order;

class Report {

    String print(Order order) {
        var out = new StringBuilder();
        for (Line line : order.lines()) {
            out.append(line.amount()).append('\n');
        }
        return out.toString();
    }
}
