package ide.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a quick pick shows while people type: by any part of the address, or by the initials of the name. */
class FilterTest {

    private static final List<String> TYPES = List.of("orders.model.Order", "orders.service.OrderBilling", "orders.model.Line");

    @Test
    void aQueryMatchesAPartOfTheAddressOrTheInitialsOfTheName() {
        assertThat(Filter.matching("", TYPES)).isEqualTo(TYPES);
        assertThat(Filter.matching("line", TYPES)).containsExactly("orders.model.Line");
        assertThat(Filter.matching("OB", TYPES)).containsExactly("orders.service.OrderBilling");
        assertThat(Filter.matching("OrdB", TYPES)).containsExactly("orders.service.OrderBilling");
        assertThat(Filter.matching("service", TYPES)).containsExactly("orders.service.OrderBilling");
    }
}
