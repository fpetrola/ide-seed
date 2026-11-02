package ide.acceptance.refactoring;

import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepsTest {

    @Test
    void resultShowsCallsAndThePrimitivesTheyMadeAsATree() {
        var ws = Workspace.of(Map.of("a/B.java", """
                package a;

                public class B {

                    private int x;

                    int get() {
                        return x;
                    }
                }
                """));

        var result = ws.invoke("extract-superclass", Map.of("type", "a.B", "name", "Base", "fields", List.of("a.B#x")));

        assertThat(result.steps()).containsExactly(
                ". extract-superclass",
                "  ' create CLASS a.Base",
                "  ' link a.B extends a.Base",
                "  ' move a.B#x -> a.Base",
                "  ' set a.Base#x visibility = package");
    }
}
