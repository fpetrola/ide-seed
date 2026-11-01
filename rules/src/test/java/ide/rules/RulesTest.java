package ide.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Datalog program as any Datalog reads it: a variable starts with a capital or {@code _}, a constant is a word in
 * lower case or a quoted text. Every program has one meaning, so one that could have two is refused when it is made.
 */
class RulesTest {

    @Test
    void itIsReadAsDatalogIsWritten() {
        var rules = Rules.parse("""
                % who comes from whom
                ancestor(X, Y) :- parent(X, Y).
                ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).
                orphan(X) :- person(X), not parent(_, X), X != "adam".
                """);

        assertThat(rules.clauses()).extracting(Clause::toString).containsExactly(
                "ancestor(X, Y) :- parent(X, Y).",
                "ancestor(X, Z) :- parent(X, Y), ancestor(Y, Z).",
                "orphan(X) :- person(X), not parent(_1, X), X != \"adam\".");
        assertThat(rules.defines("ancestor")).isTrue();
        assertThat(rules.defines("parent")).isFalse();
        assertThat(rules.recursive("ancestor")).isTrue();
        assertThat(rules.recursive("orphan")).isFalse();
    }

    @Test
    void aVariableThatNothingBindsIsRefused() {
        assertThatThrownBy(() -> Rules.parse("rich(X) :- poor(Y)."))
                .hasMessageContaining("unsafe").hasMessageContaining("X");
        assertThatThrownBy(() -> Rules.parse("odd(X) :- item(X), not other(Y)."))
                .hasMessageContaining("unsafe").hasMessageContaining("Y");
        assertThatThrownBy(() -> Rules.parse("odd(X) :- item(X), X != Y."))
                .hasMessageContaining("unsafe").hasMessageContaining("Y");
    }

    @Test
    void aRelationThatDependsOnItsOwnNegationIsRefused() {
        assertThatThrownBy(() -> Rules.parse("""
                win(X) :- move(X, Y), not win(Y).
                """)).hasMessageContaining("not stratified").hasMessageContaining("win");
    }

    @Test
    void aQueryIsAnAtomAlone() {
        assertThat(Rules.atom("parent(ana, Who)").toString()).isEqualTo("parent(\"ana\", Who)");
        assertThat(Rules.atom("parent(ana, Who)").variables()).containsExactly("Who");
    }

    @Test
    void programsAddUp() {
        var rules = Rules.parse("a(X) :- b(X).").and(Rules.parse("c(X) :- a(X)."));

        assertThat(rules.clauses()).extracting(Clause::toString).containsExactly("a(X) :- b(X).", "c(X) :- a(X).");
        assertThat(List.of(rules.defines("a"), rules.defines("c"))).containsOnly(true);
    }
}
