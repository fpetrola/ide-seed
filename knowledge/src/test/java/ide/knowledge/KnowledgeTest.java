package ide.knowledge;

import ide.engine.Plan;
import ide.engine.Repairer;
import ide.engine.Transaction;
import ide.engine.Violation;
import ide.program.ElementId;
import ide.program.Fact;
import ide.program.Kind;
import ide.program.Mode;
import ide.program.Primitive;
import ide.program.Program;
import ide.projection.Diagnostic;
import ide.projection.PrimitiveRejected;
import ide.projection.kit.MemoryProjection;
import ide.refactoring.Library;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The planner alone: goals and effects written in the test, over a projection with no language. */
class KnowledgeTest {

    /** What makes a target reachable from a site: widening it, or bringing it closer; never for fields. */
    private static final String REACH = """
            goal access.insufficient needs reachable(site, target)

            effect widen "widen ${qualified(target)} to ${v}"
              makes reachable(site, target)
              for v in wider(target)
              do
                set ${target} visibility ${v}
              end
            end

            effect bring "bring ${qualified(target)} into ${qualified(top(site))}"
              makes reachable(site, target)
              when ne(top(site), top(target))
              cost 2
              do
                move ${target} ${top(site)}
              end
            end

            effect methods-only "never offered for a field"
              makes reachable(site, target)
              when eq(kind(target), "METHOD")
              do
                fail "unreachable"
              end
            end
            """;

    /** package a: class A { int x; } class B { int n() reads A#x } */
    private static MemoryProjection program() {
        return MemoryProjection.builder().rules(MemoryProjection.PRIVATE_TO_TOP_LEVEL)
                .type("a.A").field("a.A", "x", "int")
                .type("a.B").method("a.B", "n", "int").use("a.B#n()", "a.A#x", Mode.READ)
                .build();
    }

    private static ElementId id(Program program, String address) {
        return program.find(address).orElseThrow().id();
    }

    @Test
    void plansAreDeducedFromTheEffectsThatMakeTheGoalCheapestFirst() {
        var projection = program();
        var p = projection.program();
        projection.apply(new Primitive.Set(id(p, "a.A#x"), "visibility", "private"));
        var knowledge = Knowledge.parse(REACH, Library.standard());

        var search = knowledge.search("access.insufficient", Map.of("site", id(p, "a.B#n()"), "target", id(p, "a.A#x")),
                projection);

        assertThat(search.plans()).extracting(Plan::title).containsExactly("widen a.A#x to package",
                "widen a.A#x to protected", "widen a.A#x to public", "bring a.A#x into a.B");
        assertThat(search.trace().lines()).containsExactly(
                "? reachable(a.B#n(), a.A#x)",
                "  ! widen a.A#x to package",
                "  ! widen a.A#x to protected",
                "  ! widen a.A#x to public",
                "  ! bring a.A#x into a.B",
                "  X methods-only: eq(kind(target), \"METHOD\")");
    }

    @Test
    void asARemedyItProposesAndTheRepairerKeepsWhatVerifiesWithoutUndoingTheIntent() {
        var projection = program();
        var p = projection.program();
        var x = id(p, "a.A#x");
        var tx = new Transaction(projection, new Repairer(List.of(Knowledge.parse(REACH, Library.standard()))));

        tx.apply(new Primitive.Set(x, "visibility", "private")); // B#n() reads it: access.insufficient
        var result = tx.commit();

        assertThat(result.applied()).isTrue();
        assertThat(result.repairs()).containsExactly("bring a.A#x into a.B"); // widening would undo the change
        assertThat(projection.program().element(x).qualified()).isEqualTo("a.B#x");
        assertThat(projection.program().element(x).fact(Fact.VISIBILITY)).isEqualTo("private");
    }

    @Test
    void aDiagnosticIsSearchedWithWhatItSays() {
        var knowledge = Knowledge.parse("""
                goal compiler.unknown needs known(symbol)

                effect declare "declare ${symbol}, line ${line} of ${path}"
                  makes known(symbol)
                  do
                    fail "not in this test"
                  end
                end
                """, Library.standard());
        var diagnostic = new Diagnostic("compiler.unknown", "a/A.java", 7, "total", "cannot find symbol total");

        var search = knowledge.search(diagnostic, program());

        assertThat(search.trace().lines()).containsExactly("? known(total)", "  ! declare total, line 7 of a/A.java");
        assertThat(search.plans()).extracting(Plan::title).containsExactly("declare total, line 7 of a/A.java");
    }

    @Test
    void theStandardBaseOpensWhatCannotBeAccessed() {
        var projection = program();
        var p = projection.program();
        projection.apply(new Primitive.Set(id(p, "a.A#x"), "visibility", "private"));
        var violation = new Violation("access.insufficient", id(p, "a.B#n()"), id(p, "a.A#x"), "cannot access");

        var plans = Knowledge.standard(Library.standard()).plans(violation, projection);

        assertThat(plans).extracting(Plan::title).startsWith("open-visibility a.A#x private -> package");
    }

    @Test
    void theImportFixBindsTheUnresolvedUseToATypeNamedLikeIt() {
        var projection = MemoryProjection.builder()
                .library("java.util.List", Kind.INTERFACE)
                .type("a.U").file("a.U", "a/U.java").method("a.U", "names", "void")
                .unresolved("a.U#names()", "List", Mode.DECLARED)
                .build();
        var diagnostic = new Diagnostic("javac.cant.resolve.location", "a/U.java", 4, "List", "cannot find symbol");

        var plans = Knowledge.standard(Library.standard()).plans(diagnostic, projection);
        plans.getFirst().apply(projection);

        assertThat(plans).extracting(Plan::title).containsExactly("import java.util.List");
        var p = projection.program();
        assertThat(p.unresolved()).isEmpty();
        assertThat(p.referencesFrom(id(p, "a.U#names()"))).extracting(r -> p.element(r.target()).qualified())
                .containsExactly("java.util.List");
    }

    /** Accessors for every field, all or nothing per field; or assuming none are needed. */
    private static final String ACCESSORS = """
            goal type.bare needs accessors(type)

            effect add-accessors "accessors for the fields of ${qualified(type)}"
              makes accessors(type)
              do
                for f in fields(type)
                  try "${name(f)}"
                    generate-getter ${f}
                    generate-setter ${f}
                  end
                end
              end
            end

            effect assume-none "assume ${qualified(type)} needs no accessors"
              makes accessors(type)
              cost 2
              do
                oblige "nobody needs accessors on ${qualified(type)}"
              end
            end
            """;

    @Test
    void aPlanRunsOnTheRealRunAttemptsAreUndoneAndAssumingIsRefused() {
        var projection = MemoryProjection.builder()
                .type("a.A").field("a.A", "x", "int").field("a.A", "y", "int").method("a.A", "setY", "void")
                .build();
        var a = id(projection.program(), "a.A");
        var knowledge = Knowledge.parse(ACCESSORS, Library.standard());

        var plans = knowledge.search("type.bare", Map.of("type", a), projection).plans();
        assertThat(plans).extracting(Plan::title).containsExactly("accessors for the fields of a.A",
                "assume a.A needs no accessors");

        plans.getFirst().apply(projection); // y's setter exists: its attempt is refused, and its getter undone
        var program = projection.program();
        assertThat(program.find("a.A#getX()")).isPresent();
        assertThat(program.find("a.A#setX(int)")).isPresent();
        assertThat(program.find("a.A#getY()")).isEmpty();

        assertThatThrownBy(() -> plans.getLast().apply(projection)).isInstanceOfSatisfying(PrimitiveRejected.class,
                refused -> assertThat(refused.code()).isEqualTo("plan.assumes"));
    }
}
