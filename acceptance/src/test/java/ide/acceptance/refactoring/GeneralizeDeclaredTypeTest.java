package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A field, parameter or local declared with a supertype of what it held: what it calls dispatches as before. */
class GeneralizeDeclaredTypeTest {

    private static final Map<String, String> SOURCES = Map.of("Repository.java", """
            import java.sql.SQLException;

            public interface Repository {
                void init() throws Exception;

                void close() throws SQLException;
            }
            """, "JdbcRepository.java", """
            import java.sql.SQLException;

            public class JdbcRepository implements Repository {
                public void init() throws Exception {
                }

                public void close() throws SQLException {
                }
            }
            """, "Main.java", """
            public class Main {
                static void process(JdbcRepository repository) throws Exception {
                    repository.init();
                    repository.close();
                }

                public static void main(String[] args) throws Exception {
                    JdbcRepository repository = new JdbcRepository();
                    process(repository);
                    repository.close();
                }
            }
            """);

    /** The calls bind what the methods they bound override: the same object runs the same methods. */
    @Test
    void aParameterDeclaredWithAnInterfaceItsTypeImplements() {
        var ws = Workspace.of(SOURCES);

        var result = ws.invoke("generalize-declared-type", Map.of("element", "Main#process(JdbcRepository)/repository", "type", "Repository"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("Main.java")).contains("    static void process(Repository repository) throws Exception {");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void aLocalDeclaredWithAnInterfaceItsTypeImplements() {
        var ws = Workspace.of(SOURCES);
        ws.invoke("generalize-declared-type", Map.of("element", "Main#process(JdbcRepository)/repository", "type", "Repository"));

        var result = ws.invoke("generalize-declared-type", Map.of("element", "Main#main(String[])/repository", "type", "Repository"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("Main.java")).contains("        Repository repository = new JdbcRepository();");
        assertThat(ws.errors()).isEmpty();
    }
}
