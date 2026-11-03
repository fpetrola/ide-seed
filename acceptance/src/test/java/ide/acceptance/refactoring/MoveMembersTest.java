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

    /** The methods first, the field after: in the class they all go to, they name it as its own. */
    @Test
    void aFieldMovedWhereItsUsesWentIsNamedThereAsItsOwn() {
        var ws = Workspace.of(Map.of("a/Main.java", """
                package a;

                public class Main {
                    static int count;

                    static void add() {
                        count++;
                    }

                    public static void main(String[] args) {
                        add();
                    }
                }
                """, "a/Repo.java", """
                package a;

                public class Repo {
                }
                """));

        assertThat(ws.invoke("move-members", Map.of("members", List.of("a.Main#add()"), "to", "a.Repo")).violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("Main.count++;");
        var result = ws.invoke("move-members", Map.of("members", List.of("a.Main#count"), "to", "a.Repo"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("        count++;").doesNotContain("Repo.count", "Main");
        assertThat(ws.errors()).isEmpty();
    }

    /** As IntelliJ's Move Members: a class there is not is created, beside the one they leave. */
    @Test
    void aClassThereIsNotIsCreatedBesideTheirs() {
        var ws = Workspace.of(Map.of("a/Main.java", """
                package a;

                public class Main {
                    static int count;

                    static void add() {
                        count++;
                    }

                    public static void main(String[] args) {
                        add();
                    }
                }
                """));

        var result = ws.invoke("move-members", Map.of("members", List.of("a.Main#count", "a.Main#add()"), "to", "a.Repo"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("class Repo", "static int count;", "        count++;");
        assertThat(ws.source("a/Main.java")).contains("        Repo.add();");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void inTheDefaultPackageTooAndByItsNameAlone() {
        var ws = Workspace.of(Map.of("Main.java", """
                public class Main {
                    static int count;

                    static void add() {
                        count++;
                    }

                    public static void main(String[] args) {
                        add();
                    }
                }
                """));

        var result = ws.invoke("move-members", Map.of("members", List.of("Main#add()"), "to", "Counter"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("Counter.java")).contains("class Counter", "Main.count++;");
        assertThat(ws.source("Main.java")).contains("        Counter.add();");
        assertThat(ws.errors()).isEmpty();
    }

    @Test
    void aNewClassGoesOnlyBesideTheirs() {
        var ws = Workspace.of(Map.of("a/Main.java", """
                package a;

                public class Main {
                    static void add() {
                    }
                }
                """));

        var result = ws.invoke("move-members", Map.of("members", List.of("a.Main#add()"), "to", "b.Repo"));

        assertThat(result.applied()).isFalse();
        assertThat(ws.source("a/Main.java")).contains("static void add()");
    }
}
