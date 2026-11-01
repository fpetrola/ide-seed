package ide.java;

import ide.java.semantics.EcjCheck;
import ide.projection.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Eclipse's compiler, incremental as its builder: what changed is compiled again, and what uses a type whose shape
 * changed; errors and warnings come with where they are on their line.
 */
class EcjCheckTest {

    private static final String A = """
            package a;

            import java.util.List;
            import java.util.Map;

            public class A {
                public int twice(int x) {
                    return x * 2;
                }

                List<String> names() {
                    return List.of();
                }
            }
            """;

    private static final String B = """
            package a;

            class B {
                int four(A a) {
                    return a.twice(2) + missing;
                }
            }
            """;

    @Test
    void errorsAndWarningsSayWhereTheyAreOnTheirLine() {
        var problems = new EcjCheck(List.of()).check(Map.of("a/A.java", A, "a/B.java", B));

        assertThat(problems).extracting(d -> d.code() + " " + d.severity() + " " + d.path() + ":" + d.line() + ":" + d.column()
                        + "+" + d.length() + " " + d.symbol())
                .containsExactlyInAnyOrder(
                        "ecj.UnusedImport WARNING a/A.java:4:8+13 Map",
                        "ecj.UnresolvedVariable ERROR a/B.java:5:29+7 missing");
    }

    @Test
    void onlyWhatChangedIsCompiledAgainAndWhatUsesATypeWhoseShapeChanged() {
        var check = new EcjCheck(List.of());
        var sources = new HashMap<>(Map.of("a/A.java", A, "a/B.java", B.replace(" + missing", "")));
        assertThat(check.check(sources)).extracting(Diagnostic::code).containsExactly("ecj.UnusedImport");
        assertThat(check.compiledLastTime()).containsExactlyInAnyOrder("a/A.java", "a/B.java");

        sources.put("a/A.java", A.replace("x * 2", "x + x"));
        assertThat(check.check(sources)).extracting(Diagnostic::code).containsExactly("ecj.UnusedImport");
        assertThat(check.compiledLastTime()).containsExactly("a/A.java");

        sources.put("a/A.java", A.replace("twice(int x)", "twice(long x)").replace("x * 2", "(int) x * 2"));
        assertThat(check.check(sources)).extracting(Diagnostic::code).containsExactly("ecj.UnusedImport");
        assertThat(check.compiledLastTime()).containsExactlyInAnyOrder("a/A.java", "a/B.java");

        sources.put("a/A.java", A.replace("twice", "doubled"));
        assertThat(check.check(sources)).extracting(d -> d.code() + " " + d.path()).contains("ecj.UndefinedMethod a/B.java");

        assertThat(check.check(sources)).isNotEmpty();
        assertThat(check.compiledLastTime()).isEmpty();
    }

    @Test
    void aFileGoneTakesItsProblemsAndItsTypesWithIt() {
        var check = new EcjCheck(List.of());
        check.check(Map.of("a/A.java", A, "a/B.java", B));

        assertThat(check.check(Map.of("a/B.java", B))).extracting(d -> d.code() + " " + d.symbol())
                .containsExactlyInAnyOrder("ecj.UndefinedType A", "ecj.UnresolvedVariable missing");
    }
}
