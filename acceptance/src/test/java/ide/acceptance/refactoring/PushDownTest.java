package ide.acceptance.refactoring;

import ide.engine.Violation;
import ide.session.Workspace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PushDownTest {

    private static final Map<String, String> SOURCES = Map.of(
            "a/A.java", """
                    package a;

                    class A {
                        int shared;

                        int onlyB() {
                            return 1;
                        }

                        int twice() {
                            return shared * 2;
                        }
                    }
                    """,
            "a/B.java", """
                    package a;

                    class B extends A {
                        int use() {
                            return onlyB();
                        }
                    }
                    """);

    @Test
    void pushesAMemberOnlyTheSubclassUsesDownToIt() {
        var ws = Workspace.of(SOURCES);

        var result = ws.invoke("push-down-member", Map.of("member", "a.A#onlyB()"));

        assertThat(result.violations()).isEmpty();
        assertThat(ws.source("a/A.java")).doesNotContain("onlyB");
        assertThat(ws.source("a/B.java")).contains("int onlyB() {");
        assertThat(ws.diagnostics()).isEmpty();
    }

    @Test
    void aMemberTheSuperclassItselfUsesCannotGoDown() {
        var ws = Workspace.of(SOURCES);

        var result = ws.invoke("push-down-member", Map.of("member", "a.A#shared"));

        assertThat(result.applied()).isFalse();
        assertThat(result.violations()).extracting(Violation::code).containsExactly("binding.unresolved");
        // pulling it back up would bind again, but it would undo the very move that was asked for
        assertThat(ws.whyRepair()).contains("  X pull-up-dependency a.B#shared -> a.A: would undo what the refactoring did");
    }
}
