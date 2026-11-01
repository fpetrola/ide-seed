package ide.java;

import ide.program.ElementId;
import ide.program.Program;
import ide.program.Reference;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A file edited by hand is read again; what the edit renamed or re-signed is still the element it was. */
class EditTest {

    private static final String P = """
            package a;

            public class P {
                int x;

                public P() {
                }

                int get(int scale) {
                    return x * scale;
                }
            }
            """;

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow(() -> new AssertionError("no element " + address)).id();
    }

    @Test
    void membersRenamedOrResignedByHandKeepTheirIds() {
        var projection = JavaProjection.of(Map.of("a/P.java", P));
        var before = projection.program();

        projection.edit("a/P.java", P.replace("int x;", "int count;").replace("x * scale", "count * scale")
                .replace("int get(int scale)", "int total(long factor)").replace("count * scale", "count * factor"));

        var after = projection.program();
        assertThat(id(after, "a.P#count")).isEqualTo(id(before, "a.P#x"));
        assertThat(id(after, "a.P#total(long)")).isEqualTo(id(before, "a.P#get(int)"));
        assertThat(id(after, "a.P#total(long)/factor")).isEqualTo(id(before, "a.P#get(int)/scale"));
        assertThat(after.referencesFrom(id(after, "a.P#total(long)"))).extracting(Reference::target)
                .containsExactly(id(before, "a.P#x"));
    }

    @Test
    void aTypeRenamedByHandKeepsItsIdAndItsMembersTheirs() {
        var projection = JavaProjection.of(Map.of("a/P.java", P));
        var before = projection.program();

        projection.edit("a/P.java", P.replace("class P", "class Wallet").replace("public P()", "public Wallet()"));

        var after = projection.program();
        assertThat(id(after, "a.Wallet")).isEqualTo(id(before, "a.P"));
        assertThat(id(after, "a.Wallet#x")).isEqualTo(id(before, "a.P#x"));
        assertThat(id(after, "a.Wallet#get(int)")).isEqualTo(id(before, "a.P#get(int)"));
    }

    @Test
    void anElementRemovedByHandDoesNotComeBackWithItsId() {
        var projection = JavaProjection.of(Map.of("a/P.java", P));
        var x = id(projection.program(), "a.P#x");

        projection.edit("a/P.java", P.replace("    int x;\n\n", "").replace("x * scale", "scale"));
        projection.edit("a/P.java", P);

        assertThat(id(projection.program(), "a.P#x")).isNotEqualTo(x);
    }
}
