package ide.refactor;

import ide.workbench.Workspace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepsTest {

    @Test
    void resultShowsCallsPrimitivesAndRepairsAsATree() {
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
                "  ' set a.B superclass = a.Base",
                "  . pull-up-field",
                "    ' move a.B#x -> a.Base",
                "! open-visibility a.Base#x private -> package");
    }
}
