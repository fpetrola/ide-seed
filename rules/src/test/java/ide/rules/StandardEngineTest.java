package ide.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The engine the IDE ships with: it answers the contract, and it asks the facts only what a query already knows. */
class StandardEngineTest extends RulesContract {

    @Override
    protected Engine engine() {
        return Engine.standard();
    }

    @Test
    void itAsksTheFactsWithTheValuesItAlreadyHas() {
        var patterns = new ArrayList<List<String>>();
        var facts = Facts.of(List.of(new Tuple("parent", List.of("ana", "bea")), new Tuple("parent", List.of("bea", "carla"))));
        Facts recording = (relation, pattern) -> {
            patterns.add(pattern);
            return facts.match(relation, pattern);
        };
        var rules = Rules.parse("grandparent(X, Z) :- parent(X, Y), parent(Y, Z).");

        var answers = engine().evaluate(rules, recording).answers(Rules.atom("grandparent(ana, Z)"));

        assertThat(answers).containsExactly(Map.of("Z", "carla"));
        assertThat(patterns).containsExactly(java.util.Arrays.asList("ana", null), java.util.Arrays.asList("bea", null));
    }
}
