package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A loop split in two, as Fowler's Split Loop: the first puts in a list the value each turn makes, the second does with
 * each value what came after it. It reads them all before doing anything: it says it changes meaning.
 */
class SplitLoopTest {

    private static final String ADDRESS = """
            package a;

            public class Address {
                private final String id;
                private final String street;

                public Address(String id, String street) {
                    this.id = id;
                    this.street = street;
                }

                public String getId() {
                    return id;
                }

                public String getStreet() {
                    return street;
                }
            }
            """;

    @Test
    void theValuesGoToAListAndWhatFollowedThemToALoopOverIt() {
        var ws = Workspace.of(Map.of("a/Address.java", ADDRESS, "a/Report.java", """
                package a;

                import java.sql.ResultSet;
                import java.sql.SQLException;

                class Report {
                    void print(ResultSet rs) throws SQLException {
                        while (rs.next()) {
                            Address address = new Address(rs.getString("id"), rs.getString("street"));
                            System.out.println(address.getId() + " - " + address.getStreet());
                        }
                        System.out.println("done");
                    }
                }
                """));

        var result = ws.invoke("split-loop", Map.of("loop", "a.Report#print(ResultSet)[1]", "value", "a.Report#print(ResultSet)/address",
                "name", "addresses"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Report.java")).contains("import java.util.ArrayList;", "import java.util.List;", """
                        List<Address> addresses = new ArrayList<>();
                        while (rs.next()) {
                            Address address = new Address(rs.getString("id"), rs.getString("street"));
                            addresses.add(address);
                        }
                        for (Address address : addresses) {
                            System.out.println(address.getId() + " - " + address.getStreet());
                        }
                        System.out.println("done");
                """);
        assertThat(ws.errors()).isEmpty();
        assertThat(ws.capabilities().get("split-loop").description()).startsWith("Changes meaning:");
    }
}
