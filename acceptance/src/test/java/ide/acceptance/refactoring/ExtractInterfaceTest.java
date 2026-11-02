package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractInterfaceTest {

    @Test
    void createsTheInterfaceWithTheSignaturesAndTheTypeImplementsIt() {
        var ws = Workspace.of(Map.of(
                "a/Line.java", """
                        package a;

                        public class Line {
                            int qty;
                            double price;

                            public double amount() {
                                return qty * price;
                            }

                            public String describe(String prefix, int width) {
                                return prefix + width;
                            }
                        }
                        """,
                "b/Use.java", """
                        package b;

                        class Use {
                            double total(a.Line line) {
                                return line.amount();
                            }
                        }
                        """));

        var result = ws.invoke("extract-interface", Map.of("type", "a.Line", "name", "Priced",
                "methods", List.of("a.Line#amount()", "a.Line#describe(String,int)")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Priced.java")).isEqualTo("""
                package a;

                public interface Priced {

                    double amount();

                    String describe(String prefix, int width);
                }
                """);
        assertThat(ws.source("a/Line.java")).contains("public class Line implements Priced {");
        assertThat(ws.errors()).isEmpty();
    }

    /** A type of the default package: the interface is of it too, in a file of its own at the root. */
    @Test
    void aTypeOfTheDefaultPackageGetsItsInterfaceThere() {
        var ws = Workspace.of(Map.of("JdbcRepository.java", """
                public class JdbcRepository {
                    public void init() {
                    }
                }
                """));

        var result = ws.invoke("extract-interface", Map.of("type", "JdbcRepository", "name", "Repository",
                "methods", java.util.List.of("JdbcRepository#init()")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("Repository.java")).contains("interface Repository {", "void init();");
        assertThat(ws.source("JdbcRepository.java")).contains("public class JdbcRepository implements Repository {");
        assertThat(ws.errors()).isEmpty();
    }

    /** What each method declares it throws, its signature in the interface declares too, imported there. */
    @Test
    void theSignaturesThrowWhatTheMethodsThrow() {
        var ws = Workspace.of(Map.of("a/JdbcRepository.java", """
                package a;

                import java.io.IOException;
                import java.sql.SQLException;

                public class JdbcRepository {
                    public void init() throws IOException, ClassNotFoundException, SQLException {
                        Class.forName("x");
                        throw new SQLException();
                    }

                    public void close() throws SQLException {
                    }
                }
                """));

        var result = ws.invoke("extract-interface", Map.of("type", "a.JdbcRepository", "name", "Repository",
                "methods", java.util.List.of("a.JdbcRepository#init()", "a.JdbcRepository#close()")));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repository.java")).contains("import java.io.IOException;", "import java.sql.SQLException;",
                "void init() throws IOException, ClassNotFoundException, SQLException;", "void close() throws SQLException;");
        assertThat(ws.errors()).isEmpty();
    }
}
