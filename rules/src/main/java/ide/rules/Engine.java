package ide.rules;

/**
 * What computes the answers of rules over facts. Any Datalog engine with stratified negation can be one: the
 * contract is the meaning of the rules (the tests every engine passes), not a syntax or an algorithm.
 */
public interface Engine {

    Evaluation evaluate(Rules rules, Facts facts);

    /** The engine the IDE ships with: it evaluates on demand, asking the facts only what a query already knows. */
    static Engine standard() {
        return new OnDemand();
    }
}
