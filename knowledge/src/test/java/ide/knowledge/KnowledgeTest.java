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
import ide.projection.Needs;
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
              keeps meaning
              for v in wider(target)
              do
                set ${target} visibility ${v}
              end
            end

            effect bring "bring ${qualified(target)} into ${qualified(top(site))}"
              makes reachable(site, target)
              when ne(top(site), top(target))
              keeps meaning
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

    /**
     * A predicate that definitions say, and no effect makes, is made true by changing what a definition looks at:
     * here a visibility, by the primitive that sets it. Nothing says how; what the graph has binds the rest, and a
     * definition that would need a change no primitive makes (the type a member is in) gives no way. The ways come in
     * the order the definitions are written, each said as the knowledge says it.
     */
    @Test
    void aDefinedPredicateIsMadeTrueByChangingWhatItsDefinitionLooksAt() {
        var projection = program();
        var p = projection.program();
        projection.apply(new Primitive.Set(id(p, "a.A#x"), "visibility", "private"));
        var knowledge = Knowledge.parse("""
                goal access.insufficient needs reachable(site, target)

                def reachable(site, m) when top(site, t) and top(m, t)
                def reachable(site, m) when package(site, p) and package(m, p)
                  and visibility(m, "package")
                def reachable(site, m) when kind(site, _) and visibility(m, "public")
                say visibility for reachable "open ${qualified(element)} ${was} -> ${value}"
                """, Library.standard());

        var search = knowledge.search("access.insufficient", Map.of("site", id(p, "a.B#n()"), "target", id(p, "a.A#x")),
                projection);

        assertThat(search.plans()).extracting(Plan::title).containsExactly("open a.A#x private -> package", "open a.A#x private -> public");
        assertThat(search.trace().lines()).containsExactly(
                "? reachable(a.B#n(), a.A#x)",
                "  X reachable: top(a.A#x, a.B)",
                "  ! open a.A#x private -> package",
                "  ! open a.A#x private -> public");
        assertThat(search.plans()).noneMatch(Plan::changesMeaning);

        search.plans().getFirst().apply(projection);

        assertThat(projection.program().element(id(p, "a.A#x")).fact(Fact.VISIBILITY)).isEqualTo("package");
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
    void onlyAPlanWhoseEffectsAllKeepMeaningMayRepair() {
        var knowledge = Knowledge.parse(REACH.replace("  keeps meaning\n  cost 2", "  cost 2"), Library.standard());
        var violation = new ide.engine.Violation("access.insufficient", id(program().program(), "a.B#n()"), id(program().program(), "a.A#x"), "");

        var plans = knowledge.plans(violation, program());

        assertThat(plans).filteredOn(plan -> plan.title().startsWith("widen")).allMatch(plan -> !plan.changesMeaning()).isNotEmpty();
        assertThat(plans).filteredOn(plan -> plan.title().startsWith("bring")).singleElement().matches(ide.engine.Plan::changesMeaning);
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
        var diagnostic = new Diagnostic("ecj.UndefinedType", "a/U.java", 4, "List", "cannot find symbol");

        var plans = Knowledge.standard(Library.standard()).plans(diagnostic, projection);
        plans.getFirst().apply(projection);

        assertThat(plans).extracting(Plan::title).containsExactly("import java.util.List");
        var p = projection.program();
        assertThat(p.unresolved()).isEmpty();
        assertThat(p.referencesFrom(id(p, "a.U#names()"))).extracting(r -> p.element(r.target()).qualified())
                .containsExactly("java.util.List");
    }

    @Test
    void theCreateMethodFixMakesWhatTheCallNeedsWhereTheCallLooksForIt() {
        var projection = MemoryProjection.builder()
                .type("a.U").file("a.U", "a/U.java").method("a.U", "total", "long")
                .unresolved("a.U#total()", "measure", Mode.CALL, "a.U",
                        new Needs("long", List.of(new Needs.Argument("names", "List<String>"), new Needs.Argument("i", "int"))))
                .build();
        var diagnostic = new Diagnostic("ecj.UndefinedMethod", "a/U.java", 5, "measure", "cannot find symbol");

        var plans = Knowledge.standard(Library.standard()).plans(diagnostic, projection);
        var create = plans.stream().filter(plan -> plan.title().equals("create method measure in U")).findFirst().orElseThrow();
        create.apply(projection);

        assertThat(create.asks()).extracting(Plan.Ask::defaultAnswer).containsExactly("long");
        var p = projection.program();
        var measure = p.find("a.U#measure(List<String>,int)").orElseThrow();
        assertThat(measure.fact(Fact.TYPE)).isEqualTo("long");
        assertThat(measure.fact(Fact.BODY)).isEqualTo("return default");
        assertThat(p.children(measure.id())).extracting(e -> e.name() + " " + e.fact(Fact.TYPE)).containsExactly("names List<String>", "i int");
    }

    /** Sealing a field needs it visible: either it is already, or showing it comes first. */
    private static final String CHAIN = """
            goal field.loose needs sealed(target)

            fact visible(target) when eq(attr(target, "visibility"), "public")

            effect seal "seal ${qualified(target)}"
              makes sealed(target)
              needs visible(target)
              do
                set ${target} final true
              end
            end

            effect show "show ${qualified(target)}"
              makes visible(target)
              do
                set ${target} visibility public
              end
            end
            """;

    @Test
    void whatAnEffectNeedsHoldsAlreadyOrIsMadeFirst() {
        var projection = program();
        var x = id(projection.program(), "a.A#x");
        var knowledge = Knowledge.parse(CHAIN, Library.standard());

        var search = knowledge.search("field.loose", Map.of("target", x), projection);

        assertThat(search.plans()).extracting(Plan::title).containsExactly("show a.A#x, then seal a.A#x");
        assertThat(search.trace().lines()).containsExactly(
                "? sealed(a.A#x)",
                "  ! seal a.A#x",
                "    ? visible(a.A#x)",
                "      ! show a.A#x");

        search.plans().getFirst().apply(projection);

        assertThat(projection.program().element(x).fact(Fact.VISIBILITY)).isEqualTo("public");
        assertThat(projection.program().element(x).fact(Fact.FINAL)).isEqualTo("true");
        assertThat(knowledge.search("field.loose", Map.of("target", x), projection).plans()).extracting(Plan::title)
                .containsExactly("seal a.A#x"); // visible now: nothing comes first
    }

    /** A chain p1 &lt;- p2 &lt;- ... &lt;- p{@code length}, each made by one effect that needs the next. */
    private static String chain(int length) {
        var text = new StringBuilder("goal deep needs p1(target)\n");
        for (var i = 1; i <= length; i++) {
            text.append("effect e").append(i).append(" \"e").append(i).append("\"\n  makes p").append(i).append("(target)\n");
            if (i < length) text.append("  needs p").append(i + 1).append("(target)\n");
            text.append("  do\n    fail \"not in this test\"\n  end\nend\n");
        }
        return text.toString();
    }

    @Test
    void aPlanIsAtMostFourEffectsAndASearchAnswersTheTwelveCheapest() {
        var projection = program();
        var x = Map.of("target", id(projection.program(), "a.A#x"));

        assertThat(Knowledge.parse(chain(4), Library.standard()).search("deep", x, projection).plans())
                .extracting(Plan::title).containsExactly("e4, then e3, then e2, then e1");
        assertThat(Knowledge.parse(chain(5), Library.standard()).search("deep", x, projection).plans()).isEmpty();

        var many = MemoryProjection.builder().type("a.A");
        for (var i = 0; i < 13; i++) many.field("a.A", "f" + i, "int");
        var fields = Knowledge.parse("""
                goal type.many needs touched(type)

                effect touch "touch ${name(f)}"
                  makes touched(type)
                  for f in fields(type)
                  do
                    fail "not in this test"
                  end
                end
                """, Library.standard());
        var a = many.build();

        assertThat(fields.search("type.many", Map.of("type", id(a.program(), "a.A")), a).plans()).hasSize(12);
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
