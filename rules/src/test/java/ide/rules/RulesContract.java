package ide.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What any engine answers for the same rules over the same facts: Datalog's meaning, with stratified negation. An
 * engine that passes these can take the place of another; whoever uses one sees only this.
 */
abstract class RulesContract {

    /** The engine under test. */
    protected abstract Engine engine();

    private static final Facts FAMILY = Facts.of(List.of(
            new Tuple("parent", List.of("ana", "bea")),
            new Tuple("parent", List.of("bea", "carla")),
            new Tuple("parent", List.of("carla", "dora")),
            new Tuple("parent", List.of("eva", "flor"))));

    private Evaluation evaluate(String rules, Facts facts) {
        return engine().evaluate(Rules.parse(rules), facts);
    }

    private static Atom query(String text) {
        return Rules.atom(text);
    }

    @Test
    void factsAreAnsweredByWhatTheQueryKnows() {
        var evaluation = evaluate("", FAMILY);

        assertThat(evaluation.answers(query("parent(bea, Who)"))).containsExactly(Map.of("Who", "carla"));
        assertThat(evaluation.holds(query("parent(ana, bea)"))).isTrue();
        assertThat(evaluation.holds(query("parent(bea, ana)"))).isFalse();
    }

    @Test
    void aRuleJoinsWhatItsBodySays() {
        var evaluation = evaluate("grandparent(X, Z) :- parent(X, Y), parent(Y, Z).", FAMILY);

        assertThat(evaluation.answers(query("grandparent(X, Z)"))).containsExactly(
                Map.of("X", "ana", "Z", "carla"), Map.of("X", "bea", "Z", "dora"));
        assertThat(evaluation.answers(query("grandparent(bea, Z)"))).containsExactly(Map.of("Z", "dora"));
    }

    @Test
    void recursionReachesItsFixpointAndEnds() {
        var cycle = FAMILY.with(List.of(new Tuple("parent", List.of("dora", "ana"))), List.of());
        var rules = """
                ancestor(X, Y) :- parent(X, Y).
                ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).
                """;

        assertThat(evaluate(rules, FAMILY).answers(query("ancestor(ana, Who)"))).extracting(a -> a.get("Who"))
                .containsExactlyInAnyOrder("bea", "carla", "dora");
        assertThat(evaluate(rules, cycle).answers(query("ancestor(ana, Who)"))).extracting(a -> a.get("Who"))
                .containsExactlyInAnyOrder("ana", "bea", "carla", "dora");
    }

    @Test
    void negationAsksWhatHoldsBelowIt() {
        var rules = """
                person(X) :- parent(X, _).
                person(Y) :- parent(_, Y).
                childless(X) :- person(X), not parent(X, _).
                """;

        assertThat(evaluate(rules, FAMILY).answers(query("childless(X)"))).extracting(a -> a.get("X"))
                .containsExactlyInAnyOrder("dora", "flor");
    }

    @Test
    void aComparisonKeepsWhatItSays() {
        var rules = """
                cousin-free(X, Y) :- parent(X, A), parent(Y, B), X != Y, A = A.
                """;

        assertThat(evaluate(rules, FAMILY).answers(query("cousin-free(ana, Y)"))).extracting(a -> a.get("Y"))
                .containsExactly("bea", "carla", "eva");
    }

    @Test
    void constantsWrittenInARuleAreItsOwn() {
        var rules = """
                first(X) :- parent("ana", X).
                level(X, "two") :- parent(Y, X), first(Y).
                """;

        assertThat(evaluate(rules, FAMILY).answers(query("level(X, L)"))).containsExactly(Map.of("X", "carla", "L", "two"));
    }

    @Test
    void answersComeInTheOrderTheyAreFoundWithoutRepeats() {
        var rules = """
                known(X) :- parent(X, _).
                known(X) :- parent(_, X).
                """;

        assertThat(evaluate(rules, FAMILY).answers(query("known(X)"))).extracting(a -> a.get("X"))
                .containsExactly("ana", "bea", "carla", "eva", "dora", "flor");
    }

    @Test
    void aSupposedStateLeavesTheOneItCameFromAsItWas() {
        var evaluation = evaluate("grandparent(X, Z) :- parent(X, Y), parent(Y, Z).", FAMILY);

        var supposed = evaluation.suppose(List.of(new Tuple("parent", List.of("flor", "gala"))),
                List.of(new Tuple("parent", List.of("ana", "bea"))));

        assertThat(supposed.answers(query("grandparent(X, Z)"))).containsExactly(
                Map.of("X", "bea", "Z", "dora"), Map.of("X", "eva", "Z", "gala"));
        assertThat(evaluation.answers(query("grandparent(X, Z)"))).containsExactly(
                Map.of("X", "ana", "Z", "carla"), Map.of("X", "bea", "Z", "dora"));
    }

    @Test
    void factsAreAViewAskedOnlyForTheRelationsTheRulesUse() {
        var asked = new java.util.LinkedHashSet<String>();
        Facts recording = (relation, pattern) -> {
            asked.add(relation);
            return relation.equals("unused") ? Stream.of(List.of("x")) : FAMILY.match(relation, pattern);
        };

        var answers = evaluate("grandparent(X, Z) :- parent(X, Y), parent(Y, Z).", recording).answers(query("grandparent(ana, Z)"));

        assertThat(answers).containsExactly(Map.of("Z", "carla"));
        assertThat(asked).containsExactly("parent");
    }
}
