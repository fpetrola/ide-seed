package ide.java;

import ide.program.Fact;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a declaration is annotated with, as written, separated by spaces; nothing when it has none. */
class AnnotationsTest {

    @Test
    void aDeclarationsAnnotationsAreAFactAsWritten() {
        var projection = JavaProjection.of(Map.of("a/P.java", """
                package a;

                @FunctionalInterface
                interface P {
                    @Deprecated @SuppressWarnings("x, y")
                    void run();

                    default void other() {
                    }
                }
                """));
        var program = projection.program();

        assertThat(program.find("a.P").orElseThrow().fact(Fact.ANNOTATIONS)).isEqualTo("@FunctionalInterface");
        assertThat(program.find("a.P#run()").orElseThrow().fact(Fact.ANNOTATIONS)).isEqualTo("@Deprecated @SuppressWarnings(\"x, y\")");
        assertThat(program.find("a.P#other()").orElseThrow().fact(Fact.ANNOTATIONS)).isNull();
    }
}
