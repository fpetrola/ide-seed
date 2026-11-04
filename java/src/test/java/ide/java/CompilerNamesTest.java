package ide.java;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What each name binds is what the compiler says, as it compiles: the solver only for what it leaves unbound. */
class CompilerNamesTest {

    /** A call on a pattern's variable, which JavaParser's solver does not resolve. */
    @Test
    void aUseOnAPatternsVariableIsAReference() {
        var projection = JavaProjection.of(Map.of("a/Shop.java", """
                package a;

                class Shop {
                    static class Item {
                        int price() {
                            return 2;
                        }
                    }

                    int total(Object o) {
                        return switch (o) {
                            case Item item -> item.price() + discount();
                            default -> 0;
                        };
                    }

                    int discount() {
                        return 1;
                    }
                }
                """));
        var program = projection.program();
        var total = program.find("a.Shop#total(Object)").orElseThrow().id();

        assertThat(program.referencesFrom(total)).extracting(r -> r.name()).contains("price", "discount");
    }

    /** A call that does not apply is bound by the compiler to the method it almost calls, to go on: not taken. */
    @Test
    void aCallThatDoesNotApplyBindsNothing() {
        var projection = JavaProjection.of(Map.of("a/Shop.java", """
                package a;

                class Shop {
                    int sum() {
                        return 0;
                    }

                    int total() {
                        return sum(1, 2);
                    }
                }
                """));
        var program = projection.program();

        assertThat(program.referencesTo(program.find("a.Shop#sum()").orElseThrow().id())).isEmpty();
    }
}
