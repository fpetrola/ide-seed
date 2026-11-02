package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A static method becomes an instance method of the class of one of its parameters, as IntelliJ's Convert to Instance
 * Method: the parameter is the object itself, and each call is made on what it passed for it.
 */
class ConvertToInstanceMethodTest {

    @Test
    void theParameterBecomesTheObjectAndEachCallIsMadeOnWhatItPassed() {
        var ws = Workspace.of(Map.of("a/Repo.java", """
                package a;

                public class Repo {
                    int count;
                }
                """, "a/Main.java", """
                package a;

                public class Main {
                    static void add(Repo repo, int n) {
                        repo.count += n;
                    }

                    public static void main(String[] args) {
                        Repo r = new Repo();
                        add(r, 2);
                        Main.add(new Repo(), 1);
                    }
                }
                """));

        var result = ws.invoke("convert-to-instance-method", Map.of("method", "a.Main#add(Repo,int)", "parameter", "a.Main#add(Repo,int)/repo"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("""
                    void add(int n) {
                        this.count += n;
                    }
                """);
        assertThat(ws.source("a/Main.java")).contains("        r.add(2);", "        new Repo().add(1);").doesNotContain("static void add");
        assertThat(ws.errors()).isEmpty();
    }
}
