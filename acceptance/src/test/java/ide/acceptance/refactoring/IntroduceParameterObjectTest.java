package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parameters grouped into a class the method takes instead, as IntelliJ's Introduce Parameter Object: the class has
 * a field, a getter and a constructor argument for each; each call makes one of what it passed; the method reads
 * them through it.
 */
class IntroduceParameterObjectTest {

    @Test
    void theParametersBecomeAClassTheMethodTakesAndEachCallMakes() {
        var ws = Workspace.of(Map.of("a/Store.java", """
                package a;

                public class Store {
                    void persist(String id, String name, String dni) {
                        System.out.println(id + name + dni);
                    }

                    void run(String line) {
                        String id = line.trim();
                        persist(id, "n", "d");
                    }
                }
                """));

        var result = ws.invoke("introduce-parameter-object", Map.of("method", "a.Store#persist(String,String,String)",
                "params", List.of("a.Store#persist(String,String,String)/id", "a.Store#persist(String,String,String)/name",
                        "a.Store#persist(String,String,String)/dni"), "name", "Customer", "parameter", "customer"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Store.java")).contains("""
                    void persist(Customer customer) {
                        System.out.println(customer.getId() + customer.getName() + customer.getDni());
                    }
                """, "        persist(new Customer(id, \"n\", \"d\"));");
        assertThat(ws.source("a/Customer.java")).contains("private final String id;", "public String getDni() {");
        assertThat(ws.errors()).isEmpty();
    }

    /** A method of the default package: the class is of it too. */
    @Test
    void inTheDefaultPackageTheClassIsThere() {
        var ws = Workspace.of(Map.of("Store.java", """
                public class Store {
                    static void persist(int id, String street) {
                        System.out.println(id + street);
                    }

                    public static void main(String[] args) {
                        persist(1, args[0]);
                    }
                }
                """));

        var result = ws.invoke("introduce-parameter-object", Map.of("method", "Store#persist(int,String)",
                "params", List.of("Store#persist(int,String)/id", "Store#persist(int,String)/street"), "name", "Address", "parameter", "address"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("Store.java")).contains("static void persist(Address address) {", "persist(new Address(1, args[0]));");
        assertThat(ws.source("Address.java")).contains("public class Address {");
        assertThat(ws.errors()).isEmpty();
    }
}
