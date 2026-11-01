package ide.rules;

import java.util.List;

/** {@code head :- body.}: the head holds for every way the body holds; with no body, it is a fact. */
public record Clause(Atom head, List<Literal> body) {

    public Clause {
        body = List.copyOf(body);
    }

    @Override
    public String toString() {
        return body.isEmpty() ? head + "." : head + " :- " + String.join(", ", body.stream().map(Literal::toString).toList()) + ".";
    }
}
