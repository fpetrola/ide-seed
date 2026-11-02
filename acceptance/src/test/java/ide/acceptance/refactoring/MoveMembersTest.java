package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static members moved to another class, as IntelliJ's Move Members: what they name comes with them (its imports),
 * and what used them from elsewhere names their new class.
 */
class MoveMembersTest {

    @Test
    void staticMembersGoWithTheirImportsAndTheirUsesNameTheirNewClass() {
        var ws = Workspace.of(Map.of("a/Main.java", """
                package a;

                import java.sql.Connection;
                import java.sql.DriverManager;

                public class Main {
                    static Connection conn;

                    static void init() throws Exception {
                        conn = DriverManager.getConnection("jdbc:none");
                    }

                    static void close() throws Exception {
                        conn.close();
                    }

                    public static void main(String[] args) throws Exception {
                        init();
                        close();
                    }
                }
                """, "b/Repo.java", """
                package b;

                public class Repo {
                }
                """));

        var result = ws.invoke("move-members", Map.of("members", List.of("a.Main#conn", "a.Main#init()", "a.Main#close()"), "to", "b.Repo"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("b/Repo.java")).contains("import java.sql.Connection;", "import java.sql.DriverManager;",
                "static Connection conn;", "conn = DriverManager.getConnection(\"jdbc:none\");", "conn.close();");
        assertThat(ws.source("a/Main.java")).contains("import b.Repo;", "        Repo.init();", "        Repo.close();")
                .doesNotContain("Connection conn");
        assertThat(ws.errors()).isEmpty();
    }
}
