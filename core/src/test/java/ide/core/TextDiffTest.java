package ide.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextDiffTest {

    @Test
    void whatTwoSequencesShareInOrderKeepsItsPlacesAndTheRestIsNew() {
        var before = List.of("int a = 1;", "a++;", "return a;");
        var after = List.of("log();", "int a = 1;", "a += 2;", "return a;");

        assertThat(TextDiff.common(before, after)).containsExactly(-1, 0, -1, 2);
    }

    @Test
    void aDiffShowsEachChangedRunOfLines() {
        assertThat(TextDiff.unified("a.txt", "x\ny\n", "x\nz\n")).isEqualTo("--- a.txt\n+++ a.txt\n@@ -2 +2 @@\n-y\n+z\n");
    }
}
