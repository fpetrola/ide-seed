package ide.java;

import ide.program.ElementId;
import ide.program.Kind;
import ide.program.Primitive;
import ide.program.Program;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statements of a body are elements of their method, in order, and keep who they are: an extraction takes its own
 * along and the call takes their place; an edit by hand keeps those it did not touch, by their order and text.
 */
class StatementsTest {

    private static final String A = """
            package a;

            class A {
                int total(int a, int b) {
                    int sum = a;
                    sum += b;
                    sum *= 2;
                    return sum;
                }
            }
            """;

    private static List<ElementId> statements(Program program, String callable) {
        return program.children(program.find(callable).orElseThrow().id()).stream()
                .filter(e -> e.kind() == Kind.STATEMENT).map(e -> e.id()).toList();
    }

    @Test
    void anExtractedRunTakesItsStatementsAlongAndTheCallTakesTheirPlace() {
        var projection = JavaProjection.of(Map.of("a/A.java", A));
        var before = statements(projection.program(), "a.A#total(int,int)");

        projection.apply(new Primitive.Extract(projection.program().find("a.A#total(int,int)").orElseThrow().id(), "6-7", "more"));

        var after = projection.program();
        assertThat(projection.source("a/A.java")).contains("sum = more(sum, b);");
        assertThat(statements(after, "a.A#more(int,int)")).hasSize(3).startsWith(before.get(1), before.get(2));
        var total = statements(after, "a.A#total(int,int)");
        assertThat(total).hasSize(3).startsWith(before.get(0)).endsWith(before.get(3));
        assertThat(total.get(1)).isNotIn(before);
    }

    @Test
    void anEditByHandKeepsTheStatementsItDidNotTouch() {
        var projection = JavaProjection.of(Map.of("a/A.java", A));
        var before = statements(projection.program(), "a.A#total(int,int)");

        projection.edit("a/A.java", A.replace("        int sum = a;\n", "        a++;\n        int sum = a;\n")
                .replace("sum *= 2;", "sum *= 3;"));

        var after = statements(projection.program(), "a.A#total(int,int)");
        assertThat(after).hasSize(5);
        assertThat(after.get(1)).isEqualTo(before.get(0));
        assertThat(after.get(2)).isEqualTo(before.get(1));
        assertThat(after.get(4)).isEqualTo(before.get(3));
        assertThat(List.of(after.get(0), after.get(3))).doesNotContainAnyElementsOf(before);
    }
}
