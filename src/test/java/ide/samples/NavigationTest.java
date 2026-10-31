package ide.samples;

import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationTest {

    @Test
    void callersIncludeCallsThroughTheMethodsItOverrides() throws IOException {
        var ws = Workspace.of(OrdersSampleTest.sources());

        assertThat(ws.callers("orders.model.Order#total()"))
                .containsExactly("orders.service.Billing#charge(Order)", "orders.service.Billing#label(Order)");
        assertThat(ws.callers("orders.model.Priced#total()")).containsExactly("orders.service.Billing#label(Order)");
    }

    @Test
    void hierarchyShowsSupertypesThenTheTypeThenItsSubtypes() throws IOException {
        var ws = Workspace.of(OrdersSampleTest.sources());

        assertThat(ws.hierarchy("orders.model.Order")).containsExactly(
                "orders.model.Entity",
                "orders.model.Priced",
                "> orders.model.Order");
        assertThat(ws.hierarchy("orders.model.Entity")).containsExactly(
                "> orders.model.Entity",
                "  orders.model.Customer",
                "  orders.model.Order");
    }

    @Test
    void referencesListEverySiteThatUsesAnElement() throws IOException {
        var ws = Workspace.of(OrdersSampleTest.sources());

        assertThat(ws.references("orders.model.Customer#discount"))
                .containsExactly("orders.service.Billing#charge(Order)", "orders.service.Billing#charge(Order)",
                        "orders.service.Billing#loyalty(Customer)");
    }
}
