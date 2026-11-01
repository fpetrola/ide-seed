package ide.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JSON both ways: what one side writes, the other reads, so adapters can speak it. */
class JsonTest {

    @Test
    void whatItWritesItReads() {
        var value = Map.of("id", 7L, "name", "a \"quoted\"\nline", "ok", true, "none", List.of(), "list", List.of(1.5, "x"));

        assertThat(Json.read(Json.write(value))).isEqualTo(value);
    }

    @Test
    void itReadsObjectsInTheirOrderArraysAndEveryScalar() {
        var read = (Map<?, ?>) Json.read(" {\"b\": [1, -2.5e1, \"\\u00e1\\t\"], \"a\": {\"x\": null, \"y\": false}} ");

        assertThat(read.keySet().stream().map(String::valueOf).toList()).containsExactly("b", "a");
        assertThat(read.get("b")).isEqualTo(List.of(1L, -25.0, "\u00e1\t"));
        var a = (Map<?, ?>) read.get("a");
        assertThat(a.containsKey("x")).isTrue();
        assertThat(a.get("x")).isNull();
        assertThat(a.get("y")).isEqualTo(false);
    }

    @Test
    void anythingElseIsNotJson() {
        assertThatThrownBy(() -> Json.read("{\"a\" 1}")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("':'");
        assertThatThrownBy(() -> Json.read("[1, 2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Json.read("1 2")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("the end");
    }
}
