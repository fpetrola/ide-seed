package ide.java;

import ide.program.Kind;
import ide.program.Primitive;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locals are elements of their method: two of one name in sibling blocks are two, the second written {@code i'2}; each
 * use binds its own, as a reference from the method. A local keeps its identity when its declaration moves.
 */
class LocalsTest {

    @Test
    void eachLocalIsItsOwnAndItsUsesBindIt() {
        var p = JavaProjection.of(Map.of("a/A.java", """
                package a;

                class A {
                    int x;

                    int sum(int[] values) {
                        int total = x;
                        for (int v : values) total += v;
                        for (int i = 0; i < 2; i++) total += i;
                        for (int i = 0; i < 3; i++) total -= i * 2;
                        return total;
                    }
                }
                """)).program();
        var sum = p.find("a.A#sum(int[])").orElseThrow().id();

        assertThat(p.children(sum)).filteredOn(e -> e.kind() == Kind.LOCAL).extracting(e -> e.qualified())
                .containsExactly("a.A#sum(int[])/total", "a.A#sum(int[])/v", "a.A#sum(int[])/i", "a.A#sum(int[])/i'2");
        var second = p.find("a.A#sum(int[])/i'2").orElseThrow().id();
        assertThat(p.referencesFrom(sum)).filteredOn(r -> r.target().equals(second)).extracting(r -> r.mode().label())
                .containsExactly("read", "update", "read");
        assertThat(p.referencesFrom(sum)).filteredOn(r -> r.name().equals("total")).extracting(r -> r.mode().label())
                .containsExactly("update", "update", "update", "read");
    }

    @Test
    void anExtractedLoopTakesItsLocalAlongAndThoseLeftCloseRanks() {
        var projection = JavaProjection.of(Map.of("a/A.java", """
                package a;

                class A {
                    int sum() {
                        int total = 0;
                        for (int i = 0; i < 2; i++) total += i;
                        for (int i = 0; i < 3; i++) total -= i;
                        for (int i = 0; i < 4; i++) total *= i;
                        return total;
                    }
                }
                """));
        var before = projection.program();
        var sum = before.find("a.A#sum()").orElseThrow().id();
        var second = before.find("a.A#sum()/i'2").orElseThrow().id();
        var third = before.find("a.A#sum()/i'3").orElseThrow().id();

        var change = projection.apply(new Primitive.Extract(sum, "7-7", "less"));

        var after = projection.program();
        assertThat(after.element(second).qualified()).isEqualTo("a.A#less(int)/i");
        assertThat(after.element(third).qualified()).isEqualTo("a.A#sum()/i'2");
        assertThat(after.referencesTo(third)).hasSize(3).allMatch(r -> r.site().equals(sum));
        assertThat(after.referencesTo(second)).hasSize(3).allMatch(r -> r.site().equals(change.produced()));
        assertThat(before.expecting(change).diff(after).lost()).isEmpty();
    }
}
