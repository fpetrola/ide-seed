package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A project read from its own folder, with its sources under a source root: a new type's file goes in that root. */
class SourceRootTest {

    @Test
    void aClassCreatedInTheDefaultPackageGoesInTheSourceRoot() {
        var ws = Workspace.of(Map.of("src/main/java/Store.java", """
                public class Store {
                    static void persist(int id, String street) {
                        System.out.println(id + street);
                    }
                }
                """));

        var result = ws.invoke("introduce-parameter-object", Map.of("method", "Store#persist(int,String)",
                "params", List.of("Store#persist(int,String)/id", "Store#persist(int,String)/street"), "name", "Address", "parameter", "address"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.sources()).containsKey("src/main/java/Address.java").doesNotContainKey("Address.java");
    }

    @Test
    void aTypeMovedToAPackageGoesInItsRootToo() {
        var ws = Workspace.of(Map.of("src/main/java/a/Money.java", """
                package a;

                public class Money {
                }
                """, "src/main/java/b/Other.java", """
                package b;

                public class Other {
                }
                """));

        var result = ws.invoke("move-type", Map.of("type", "a.Money", "package", "b"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.sources()).containsKey("src/main/java/b/Money.java").doesNotContainKey("b/Money.java");
    }
}
