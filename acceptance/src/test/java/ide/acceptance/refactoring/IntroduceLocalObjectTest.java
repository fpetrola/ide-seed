package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locals made one object of an existing class with one of its constructors, as Introduce Parameter Object does with
 * parameters: each read of one is the getter of the field the constructor gives it to, and its value goes to the
 * constructor, evaluated in the order it was.
 */
class IntroduceLocalObjectTest {

    @Test
    void theLocalsBecomeTheObjectAndTheirReadsItsGetters() {
        var ws = Workspace.of(Map.of("a/Address.java", """
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
                """, "a/Report.java", """
                package a;

                import java.sql.ResultSet;
                import java.sql.SQLException;

                class Report {
                    void print(ResultSet rs) throws SQLException {
                        while (rs.next()) {
                            String id = rs.getString("id");
                            String street = rs.getString("street");
                            System.out.println(id + " - " + street);
                        }
                    }
                }
                """));

        var result = ws.invoke("introduce-local-object", Map.of("constructor", "a.Address#new(String,String)",
                "locals", List.of("a.Report#print(ResultSet)/id", "a.Report#print(ResultSet)/street"), "name", "address"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Report.java")).contains("""
                        while (rs.next()) {
                            Address address = new Address(rs.getString("id"), rs.getString("street"));
                            System.out.println(address.getId() + " - " + address.getStreet());
                        }
                """);
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void aLocalNothingReadsGoesToTheConstructorToo() {
        var ws = Workspace.of(Map.of("a/Address.java", ADDRESS, "a/Report.java", """
                package a;

                import java.sql.ResultSet;
                import java.sql.SQLException;

                class Report {
                    void print(ResultSet rs) throws SQLException {
                        while (rs.next()) {
                            String id = rs.getString("id");
                            String street = rs.getString("street");
                            System.out.println(street);
                        }
                    }
                }
                """));

        var result = ws.invoke("introduce-local-object", Map.of("constructor", "a.Address#new(String,String)",
                "locals", List.of("a.Report#print(ResultSet)/id", "a.Report#print(ResultSet)/street"), "name", "address"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Report.java")).contains("""
                        while (rs.next()) {
                            Address address = new Address(rs.getString("id"), rs.getString("street"));
                            System.out.println(address.getStreet());
                        }
                """);
        assertThat(ws.errors()).isEmpty();
    }

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

    /** A parameter no local gives takes a value, as a new parameter's calls do in change-signature. */
    @Test
    void aParameterNoLocalGivesTakesAValue() {
        var ws = Workspace.of(Map.of("a/Address.java", ADDRESS, "a/Report.java", """
                package a;

                import java.sql.ResultSet;
                import java.sql.SQLException;
                import java.util.List;

                class Report {
                    void print(ResultSet rs, List<String> fields) throws SQLException {
                        if (fields.isEmpty()) {
                            String id = "none";
                            System.out.println(id);
                        }
                        while (rs.next()) {
                            String street = rs.getString("street");
                            System.out.println(street);
                        }
                    }
                }
                """));

        var result = ws.invoke("introduce-local-object", Map.of("constructor", "a.Address#new(String,String)",
                "locals", List.of("a.Report#print(ResultSet,List<String>)/street"), "name", "address", "values", "id = fields.get(4)"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Report.java")).contains("""
                        while (rs.next()) {
                            Address address = new Address(fields.get(4), rs.getString("street"));
                            System.out.println(address.getStreet());
                        }
                """);
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void everyParameterIsGivenOnce() {
        var ws = Workspace.of(Map.of("a/Address.java", ADDRESS, "a/Report.java", """
                package a;

                class Report {
                    void print(String id, String street) {
                        String s = street;
                        System.out.println(s);
                    }
                }
                """));

        var result = ws.invoke("introduce-local-object", Map.of("constructor", "a.Address#new(String,String)",
                "locals", List.of("a.Report#print(String,String)/s"), "name", "address", "values", "id = street, street = id"));

        assertThat(result.applied()).isFalse();
        assertThat(ws.source("a/Report.java")).contains("String s = street;");
    }
}
