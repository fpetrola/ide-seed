package ide.java;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What the name solver cannot model, even by recursing without end, leaves a use unknown: the rest is read. */
class SolverFailuresTest {

    /** An anonymous class of an inner one made on what a call answers: the solver takes it as its own "this". */
    @Test
    void aUseTheSolverRecursesOnIsUnknownAndTheRestIsRead() {
        var projection = JavaProjection.of(Map.of("a/Outer.java", """
                package a;

                class Outer {
                    class Inner {
                        void run() {
                        }
                    }

                    Outer outer() {
                        return this;
                    }

                    void start() {
                        outer().new Inner() {
                            @Override
                            void run() {
                                start();
                            }
                        };
                    }

                    void stop() {
                        start();
                    }
                }
                """));

        assertThat(projection.program().referencesTo(projection.program().elements().stream()
                .filter(e -> e.name().equals("start")).findFirst().orElseThrow().id()))
                .anySatisfy(use -> assertThat(projection.program().element(use.site()).name()).isEqualTo("stop"));
    }
}
