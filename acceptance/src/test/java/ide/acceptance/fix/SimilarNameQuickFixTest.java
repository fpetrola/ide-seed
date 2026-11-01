package ide.acceptance.fix;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A name misspelled: the fixes offer the members whose names are almost it, as IDEs do. */
class SimilarNameQuickFixTest {

    private static final String U = """
            package a;

            class U {
                int count;

                int total() {
                    return cuont + sizee();
                }

                int size() {
                    return 0;
                }
            }
            """;

    @Test
    void aMisspelledFieldIsChangedToTheOneItAlmostNames() {
        var ws = Workspace.of(Map.of("a/U.java", U));

        assertThat(ws.fixes("a/U.java", 7)).contains("change to count", "change to size(..)");

        ws.fix("a/U.java", 7, "change to count");
        ws.fix("a/U.java", 7, "change to size(..)");

        assertThat(ws.errors()).isEmpty();
        assertThat(ws.source("a/U.java")).contains("return count + size();");
    }

    @Test
    void aMemberTheSiteCannotAccessIsNotOffered() {
        var ws = Workspace.of(Map.of("a/U.java", """
                package a;

                class U {
                    int twice(b.V v) {
                        return v.cuont * 2;
                    }
                }
                """, "b/V.java", """
                package b;

                public class V {
                    private int count;
                }
                """));

        assertThat(ws.fixes("a/U.java", 5)).doesNotContain("change to count");
    }
}
