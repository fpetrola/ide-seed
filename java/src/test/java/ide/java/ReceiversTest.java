package ide.java;

import ide.program.Reference;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a use is written: on the object itself (alone, or through {@code this} or {@code super}), through a type, or
 * through another object. A static method may use what needs an object only through one.
 */
class ReceiversTest {

    @Test
    void eachUseSaysWhatItIsMadeOn() {
        var projection = JavaProjection.of(Map.of("a/A.java", """
                package a;

                class A {
                    int x;
                    static int count;

                    int all() {
                        return x + this.x + count + A.count + other().x + twice() + this.twice() + A.once() + super.hashCode();
                    }

                    A other() {
                        return new A();
                    }

                    int twice() {
                        return 2;
                    }

                    static int once() {
                        return 1;
                    }
                }
                """));
        var p = projection.program();
        var all = p.find("a.A#all()").orElseThrow().id();

        assertThat(p.referencesFrom(all)).filteredOn(r -> r.mode() != ide.program.Mode.TYPE)
                .extracting(r -> r.name() + " " + r.receiver()).containsExactlyInAnyOrder(
                        "x SELF", "x SELF", "count SELF", "count TYPE", "other SELF", "x OTHER", "twice SELF", "twice SELF", "once TYPE");
        assertThat(p.referencesFrom(all)).filteredOn(r -> r.mode() == ide.program.Mode.TYPE).extracting(Reference::receiver)
                .containsOnly(Reference.Receiver.NONE);
        assertThat(p.referencesFrom(p.find("a.A#other()").orElseThrow().id())).extracting(Reference::receiver)
                .containsOnly(Reference.Receiver.NONE);
    }
}
