package ide.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A file before and after, as two columns of rows side by side: what stayed on both sides, what went only on the left,
 * what came only on the right, a line changed on both on one row, and lines that went from one place and came to
 * another (another indentation too) as moved on both. A row of what a step takes is in its zone; a change outside it
 * is also changed.
 */
class SideBySideTest {

    private static List<String> rows(SideBySide compared) {
        return compared.rows().stream().map(r -> r.leftKind() + "|" + r.left() + "|" + r.rightKind() + "|" + r.right()
                + (r.zone() ? "|zone" : "")).toList();
    }

    @Test
    void whatStaysGoesComesAndChangesIsAlignedOnRows() {
        var compared = SideBySide.of("a\nb\nc\nd\n", "a\nB\nc\nd\ne\n", Set.of());

        assertThat(rows(compared)).containsExactly("same|a|same|a", "changed|b|changed|B", "same|c|same|c", "same|d|same|d",
                "none||added|e");
    }

    @Test
    void linesThatGoFromAPlaceAndComeToAnotherAreMoved() {
        var compared = SideBySide.of("""
                void m() {
                    int x = 1;
                    print(x);
                }
                """, """
                void m() {
                    n();
                }

                void n() {
                        int x = 1;
                        print(x);
                }
                """, Set.of(2, 3));

        assertThat(rows(compared)).contains("moved|    int x = 1;|none||zone", "none||moved|        int x = 1;");
        assertThat(compared.rows()).filteredOn(r -> r.rightKind().equals("added")).extracting(SideBySide.Row::right)
                .contains("    n();", "void n() {");
    }

    @Test
    void aChangeOutsideTheZoneIsAlsoChanged() {
        var compared = SideBySide.of("a\nb\nc\nd\ne\n", "a\nB\nc\nd\nE\n", Set.of(2));

        assertThat(compared.also()).extracting(SideBySide.Also::line).containsExactly(5);
    }

    @Test
    void aNewFileIsAllOnTheRight() {
        var compared = SideBySide.of(null, "class C {\n}\n", Set.of());

        assertThat(rows(compared)).containsExactly("none||added|class C {", "none||added|}");
    }
}
