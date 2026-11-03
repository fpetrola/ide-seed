package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A statement moved up or down its block, as IntelliJ's Move Statement, only past statements it can run in either order
 * with: neither gives a value the other uses, and what one does cannot change what the other reads or does.
 */
class MoveStatementTest {

    private static final String CUSTOMER = """
            package a;

            public class Customer {
                private final String id;
                private final String name;

                public Customer(String id, String name) {
                    this.id = id;
                    this.name = name;
                }

                public String getName() {
                    return name;
                }
            }
            """;

    @Test
    void aNewObjectThatOnlyKeepsItsValuesGoesUpPastReads() {
        var ws = Workspace.of(Map.of("a/Customer.java", CUSTOMER, "a/Report.java", """
                package a;

                import java.sql.ResultSet;
                import java.sql.SQLException;
                import java.util.List;

                class Report {
                    void print(ResultSet rs, List<String> fields) throws SQLException {
                        String number = fields.get(3);
                        String street = fields.get(2);
                        Customer customer = new Customer(fields.get(4), rs.getString("name"));
                        System.out.println(number + street + customer.getName());
                    }
                }
                """));

        var result = ws.invoke("move-statement", Map.of("statement", "a.Report#print(ResultSet,List<String>)[3]",
                "before", "a.Report#print(ResultSet,List<String>)[1]"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Report.java")).contains("""
                        Customer customer = new Customer(fields.get(4), rs.getString("name"));
                        String number = fields.get(3);
                        String street = fields.get(2);
                """);
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void aNewObjectThatDoesNothingGoesUpPastACall() {
        var ws = Workspace.of(Map.of("a/Reader.java", """
                package a;

                public class Reader {
                    String read() {
                        return "";
                    }
                }
                """, "a/Main.java", """
                package a;

                class Main {
                    static void init() {
                        System.out.println("init");
                    }

                    void run() {
                        init();
                        Reader reader = new Reader();
                        System.out.println(reader.read());
                    }
                }
                """));

        var result = ws.invoke("move-statement", Map.of("statement", "a.Main#run()[2]", "before", "a.Main#run()[1]"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Main.java")).contains("""
                        Reader reader = new Reader();
                        init();
                """);
    }

    @Test
    void notPastWhatGivesAValueItUses() {
        var ws = Workspace.of(Map.of("a/Main.java", """
                package a;

                class Main {
                    void run() {
                        int a = 1;
                        int b = a + 1;
                        System.out.println(b);
                    }
                }
                """));

        var result = ws.invoke("move-statement", Map.of("statement", "a.Main#run()[2]", "before", "a.Main#run()[1]"));

        assertThat(result.applied()).isFalse();
        assertThat(ws.source("a/Main.java")).contains("int a = 1;\n        int b = a + 1;");
    }

    @Test
    void notACallPastAnother() {
        var ws = Workspace.of(Map.of("a/Main.java", """
                package a;

                class Main {
                    void run() {
                        System.out.println("one");
                        System.out.println("two");
                        System.out.println("three");
                    }
                }
                """));

        var down = ws.invoke("move-statement", Map.of("statement", "a.Main#run()[1]", "before", "a.Main#run()[3]"));

        assertThat(down.applied()).isFalse();
        assertThat(ws.source("a/Main.java")).contains("System.out.println(\"one\");\n        System.out.println(\"two\");");
    }
}
