package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A member's visibility changed, as IntelliJ's Change Visibility: refused if a use would no longer reach it. */
class ChangeVisibilityTest {

    private static final String REPO = """
            package a;

            public class Repo {
                int count;

                int next() {
                    return ++count;
                }
            }
            """;

    @Test
    void aFieldOnlyItsClassUsesBecomesPrivate() {
        var ws = Workspace.of(Map.of("a/Repo.java", REPO));

        var result = ws.invoke("change-visibility", Map.of("element", "a.Repo#count", "visibility", "private"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/Repo.java")).contains("    private int count;");
    }

    @Test
    void notWhenAUseElsewhereWouldNoLongerReachIt() {
        var ws = Workspace.of(Map.of("a/Repo.java", REPO, "a/User.java", """
                package a;

                class User {
                    int use(Repo r) {
                        return r.count;
                    }
                }
                """));

        var result = ws.invoke("change-visibility", Map.of("element", "a.Repo#count", "visibility", "private"));

        assertThat(result.applied()).isFalse();
        assertThat(ws.source("a/Repo.java")).contains("    int count;");
    }
}
